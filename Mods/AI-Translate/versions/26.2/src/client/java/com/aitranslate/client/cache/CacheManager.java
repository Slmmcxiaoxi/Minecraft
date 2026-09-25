package com.aitranslate.client.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.config.ModConfig;
import com.aitranslate.client.util.FileUtil;

/**
 * Owns the per-world / per-server cache buckets and the in-memory index of the
 * currently active bucket. All methods that touch the active bucket must be
 * called from the client thread.
 */
public class CacheManager {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	private final ModConfig config;
	private final MemoryCache memory;
	private final ActiveCache view = new ActiveCache();
	private final AtomicBoolean dirty = new AtomicBoolean(false);
	private final AtomicLong lastSave = new AtomicLong(0L);
	/** Single background thread that writes the cache to disk (never the game thread). */
	private final java.util.concurrent.ExecutorService diskWriter =
			java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "ai-translate-disk");
				thread.setDaemon(true);
				return thread;
			});
	private final AtomicBoolean saveInFlight = new AtomicBoolean(false);
	/** True once the active bucket has been read from disk (background load). */
	private final AtomicBoolean ready = new AtomicBoolean(true);

	private CacheContext context;
	private DiskCache disk;

	public CacheManager(ModConfig config) {
		this.config = config;
		// The limit is read from the config on every eviction, so changing it in the
		// settings takes effect immediately.
		this.memory = new MemoryCache(() -> config.memoryCacheSize);
	}

	public Path getRootDir() {
		Path root = Path.of(config.cacheRoot);
		if (root.isAbsolute()) {
			return root;
		}
		// Relative paths are resolved against the game directory (.minecraft).
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(root);
	}

	public CacheContext currentContext() {
		return context;
	}

	/**
	 * Point the cache at another world/server. The previous bucket is flushed to disk
	 * first.
	 * <p>
	 * The new bucket is read <strong>on a background thread</strong>: entering a world
	 * used to freeze for a moment because the whole cache file was parsed on the
	 * render thread while the game was still loading chunks (sixth feedback round).
	 * Until the load finishes {@link #isReady()} is false and lookups simply miss, so
	 * the scheduler's warm-up window can hold requests back instead of asking the API
	 * for text that is about to arrive from disk.
	 */
	public synchronized void switchContext(CacheContext next) {
		if (next == null) {
			if (context == null) return;
			flushBlocking(2000L);
			context = null;
			disk = null;
			memory.clear();
			dirty.set(false);
			ready.set(true);
			return;
		}
		if (context != null && context.equals(next)) {
			return;
		}
		flushBlocking(2000L);
		context = next;
		DiskCache target = new DiskCache(getRootDir().resolve(next.directoryName()), next);
		disk = target;
		memory.clear();
		ready.set(false);
		long start = System.currentTimeMillis();
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		diskWriter.execute(() -> {
			try {
				List<CacheEntry> entries = target.load();
				Runnable install = () -> {
					if (target != disk) {
						return; // another world was entered in the meantime
					}
					memory.putAll(entries);
					ready.set(true);
					LOGGER.info("AI Translate cache loaded for {} ({} entries in {} ms{})", next, entries.size(),
							System.currentTimeMillis() - start,
							memory.droppedEchoes() == 0 ? ""
									: ", " + memory.droppedEchoes()
											+ " stored echo answer(s) dropped so the text is translated again");
				};
				if (minecraft != null) {
					minecraft.execute(install);
				} else {
					install.run();
				}
			} catch (RuntimeException e) {
				LOGGER.warn("Could not load the cache for {}", next, e);
				ready.set(true);
			}
		});
	}

	/** True once the active bucket has been read from disk (see {@link #switchContext}). */
	public boolean isReady() {
		return ready.get();
	}

	/** Translation store of the active bucket; writes are marked dirty. */
	public TranslationCache current() {
		return view;
	}

	public int currentSize() {
		return memory.size();
	}

	/** Cache hit rate in percent (performance summary). */
	public long hitRatePercent() {
		return memory.hitRatePercent();
	}

	public Map<TextType, Integer> currentTypeCounts() {
		Map<TextType, Integer> counts = new EnumMap<>(TextType.class);
		for (TextType type : TextType.values()) {
			counts.put(type, 0);
		}
		for (CacheEntry entry : memory.entries()) {
			TextType type = DiskCache.typeOf(entry.textType);
			counts.merge(type, 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * Snapshots the active bucket and writes it on the background disk thread, so
	 * the game thread never waits for file IO. Concurrent requests are coalesced:
	 * while a write is in flight the cache simply stays dirty and is written again
	 * afterwards.
	 */
	public synchronized void saveCurrent() {
		if (disk == null || !dirty.getAndSet(false)) {
			return;
		}
		List<CacheEntry> snapshot = new ArrayList<>(memory.entries());
		DiskCache target = disk;
		if (saveInFlight.get()) {
			// A write is running; keep the dirty flag so the next save picks it up.
			dirty.set(true);
			return;
		}
		saveInFlight.set(true);
		diskWriter.execute(() -> {
			try {
				target.save(snapshot);
			} catch (RuntimeException e) {
				LOGGER.warn("Background cache save failed", e);
			} finally {
				saveInFlight.set(false);
				lastSave.set(System.currentTimeMillis());
			}
		});
	}

	/**
	 * Waits for the background writer (used when leaving a world, when exporting and
	 * when shutting down) and keeps writing until nothing is dirty any more.
	 * <p>
	 * The loop matters: {@link #saveCurrent()} coalesces - while a write is in flight it
	 * only leaves the dirty flag set, and the in-flight write carries the snapshot from
	 * <em>before</em> the newest translations arrived. Waiting just once therefore
	 * returned with entries still unwritten, which is how an export could be short of
	 * the last few translations (found by the fifteenth round's cache probe: 103 entries
	 * in memory, 101 in the exported file).
	 */
	public void flushBlocking(long timeoutMs) {
		long deadline = System.currentTimeMillis() + Math.max(100L, timeoutMs);
		do {
			saveCurrent();
			while (saveInFlight.get() && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(10L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		} while (dirty.get() && System.currentTimeMillis() < deadline);
	}

	/** Persist at most once every {@code intervalMs} (called from the tick loop). */
	public void saveIfDue(long intervalMs) {
		if (dirty.get() && System.currentTimeMillis() - lastSave.get() > intervalMs) {
			saveCurrent();
		}
	}

	public long lastSaveTime() {
		return lastSave.get();
	}

	public boolean isDirty() {
		return dirty.get();
	}

	/**
	 * Drops the in-memory index only: the files on disk stay, so the translations
	 * are still there after a restart or a context reload. Used by the manual
	 * refresh key ("clear the memory cache when refreshing").
	 */
	public synchronized int clearMemory() {
		int dropped = memory.size();
		memory.clear();
		return dropped;
	}

	public synchronized void clearCurrent() {
		memory.clear();
		if (disk != null) {
			try (Stream<Path> files = Files.list(disk.directory())) {
				for (Path file : files.toList()) {
					Files.deleteIfExists(file);
				}
			} catch (IOException e) {
				LOGGER.warn("Failed to clear cache directory {}", disk.directory(), e);
			}
			disk.writeMetadata(0, System.currentTimeMillis());
		}
		dirty.set(false);
	}

	public synchronized void clearAll() {
		memory.clear();
		dirty.set(false);
		Path root = getRootDir();
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to clear cache root {}", root, e);
		}
	}

	/** One row of the cache-management screen. */
	public record ContextStats(String directory, String name, String type, int entries, long size, long lastUpdated) {
	}

	public List<ContextStats> listContexts() {
		List<ContextStats> stats = new ArrayList<>();
		Path root = getRootDir();
		if (!Files.isDirectory(root)) {
			return stats;
		}
		for (CacheContext.ContextType type : CacheContext.ContextType.values()) {
			Path base = root.resolve(type.name().toLowerCase(java.util.Locale.ROOT));
			if (!Files.isDirectory(base)) {
				continue;
			}
			try (Stream<Path> dirs = Files.list(base)) {
				for (Path dir : dirs.filter(Files::isDirectory).toList()) {
					int entries = 0;
					long updated = 0L;
					Path metadata = dir.resolve("metadata.json");
					if (Files.isRegularFile(metadata)) {
						try {
							var json = FileUtil.readJson(metadata).getAsJsonObject();
							entries = json.has("totalEntries") ? json.get("totalEntries").getAsInt() : 0;
							updated = json.has("lastUpdatedAt") ? json.get("lastUpdatedAt").getAsLong() : 0L;
						} catch (IOException | RuntimeException ignored) {
							// counted below
						}
					}
					if (entries == 0) {
						entries = countEntries(dir);
					}
					stats.add(new ContextStats(
							type.name().toLowerCase(java.util.Locale.ROOT) + "/" + dir.getFileName(),
							dir.getFileName().toString(),
							type.name(),
							entries,
							FileUtil.directorySize(dir),
							updated));
				}
			} catch (IOException e) {
				LOGGER.warn("Failed to list cache contexts in {}", base, e);
			}
		}
		stats.sort((a, b) -> Long.compare(b.lastUpdated(), a.lastUpdated()));
		return stats;
	}

	private int countEntries(Path dir) {
		int total = 0;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")
					&& !"metadata.json".equals(p.getFileName().toString())).toList()) {
				try {
					var root = FileUtil.readJson(file);
					if (root.isJsonObject()) {
						var object = root.getAsJsonObject();
						var map = object.has("entries") && object.get("entries").isJsonObject()
								? object.getAsJsonObject("entries") : object;
						total += map.size();
					}
				} catch (IOException | RuntimeException ignored) {
					// ignore broken file
				}
			}
		} catch (IOException ignored) {
			// ignore
		}
		return total;
	}

	public void deleteContext(String directory) {
		if (directory == null || directory.isBlank()) {
			return;
		}
		if (isCurrent(directory)) {
			// Fifteenth feedback round: deleting the world the player is standing in must
			// not make its cache unreadable while it is in use. The bucket is emptied
			// instead of removed - the row stays in the list (with 0 entries), the running
			// game keeps a valid directory to write to, and the player can see that the
			// deletion happened.
			flushBlocking(1000L);
			clearCurrent();
			LOGGER.info("Cleared the cache of the current context {}", directory);
			return;
		}
		deleteDir(getRootDir().resolve(directory));
	}

	/** True when this directory name is the cache bucket of the world that is open. */
	public boolean isCurrent(String directory) {
		return directory != null && context != null && directory.equals(context.directoryName());
	}

	/** The directory name of the bucket in use, or {@code null} when no world is open. */
	public String currentDirectory() {
		return context == null ? null : context.directoryName();
	}

	// ------------------------------------------------- JSON export / import (1.3)

	/** Where exported JSON files are written (fixed folder, so they are findable). */
	public Path exportDirectory() {
		return getRootDir().resolve("exports");
	}


	/** Writes a bundle as JSON (UTF-8, no BOM). */
	public Path writeBundle(CacheBundle bundle, Path target) throws IOException {
		Files.createDirectories(target.getParent());
		FileUtil.writeJson(target, bundle.toJson(System.currentTimeMillis()));
		return target;
	}

	/** Reads a bundle from a JSON file. */
	public CacheBundle readBundle(Path file) throws IOException {
		return CacheBundle.fromJson(FileUtil.readJson(file), "singleplayer/imported");
	}

	/** One world's cache as a bundle (the per-row 导出 of the cache page). */
	public CacheBundle exportContext(String directory) {
		if (isCurrent(directory)) {
			flushBlocking(2000L);
		}
		CacheBundle bundle = CacheBundle.empty();
		Path dir = getRootDir().resolve(directory);
		for (CacheEntry entry : new DiskCache(dir, contextFor(directory)).load()) {
			bundle.entries(directory).put(entry.key(), entry);
		}
		return bundle;
	}

	/** What an import did, for the report on screen. */
	public record ImportReport(int added, int replaced, int kept, int contexts) {
		public String describe() {
			return "新增 " + added + " 条，更新 " + replaced + " 条，保留 " + kept + " 条";
		}
	}

	/**
	 * Merges one exported file into <em>one</em> world (the row the player pressed
	 * 导入 on).
	 * <p>
	 * The file's context of the same name is used when it has one. A file with exactly
	 * one context is merged into the target as well - that is what makes "export from
	 * world A, import into world B" work, which is the whole point of a portable file.
	 * A multi-world file that does not contain the target is reported as such instead
	 * of dumping unrelated entries into the world.
	 *
	 * @return what was merged, or an empty report when the file has nothing for this
	 *         world ({@code contexts} is then {@code 0} and the message says so)
	 */
	public ImportReport importInto(Path file, String directory) throws IOException {
		if (directory == null || directory.isBlank()) {
			return new ImportReport(0, 0, 0, 0);
		}
		CacheBundle bundle = readBundle(file);
		var entries = bundle.entries(directory);
		if (entries.isEmpty()) {
			List<String> names = bundle.contextNames();
			// A single-context file is taken as "this world's entries": the world may have
			// been renamed, or the file may come from another installation.
			if (names.size() == 1) {
				entries = bundle.entries(names.get(0));
			} else {
				return new ImportReport(0, 0, 0, 0);
			}
		}
		return mergeIntoContext(directory, entries);
	}

	/** One context: read it from disk, merge (newer wins) and write it back. */
	private synchronized ImportReport mergeIntoContext(String directory, Map<String, CacheEntry> incoming) {
		if (incoming == null || incoming.isEmpty()) {
			return new ImportReport(0, 0, 0, 0);
		}
		boolean current = isCurrent(directory);
		if (current) {
			// Entries that are only in memory have to reach the disk first, otherwise the
			// merge would compare against an outdated file and could drop them.
			flushBlocking(2000L);
		}
		DiskCache target = new DiskCache(getRootDir().resolve(directory), contextFor(directory));
		Map<String, CacheEntry> merged = new java.util.LinkedHashMap<>();
		for (CacheEntry entry : target.load()) {
			merged.put(entry.key(), entry);
		}
		CacheBundle.MergeResult result = CacheBundle.mergeInto(merged, incoming);
		if (result.added() + result.replaced() > 0) {
			target.save(merged.values());
		}
		if (current) {
			// Reading the bucket back keeps the running game consistent with the file.
			memory.clear();
			memory.putAll(target.load());
			dirty.set(false);
		}
		LOGGER.info("Imported cache for {}: {}", directory, result);
		return new ImportReport(result.added(), result.replaced(), result.kept(), 1);
	}

	/**
	 * A {@link CacheContext} for a directory name ({@code singleplayer/<world>}), used
	 * for the metadata of a bucket that is not the active one.
	 */
	private CacheContext contextFor(String directory) {
		if (context != null && directory.equals(context.directoryName())) {
			return context;
		}
		int slash = directory == null ? -1 : directory.indexOf('/');
		String type = slash < 0 ? directory : directory.substring(0, slash);
		String identifier = slash < 0 ? "" : directory.substring(slash + 1);
		CacheContext.ContextType contextType = "multiplayer".equalsIgnoreCase(type)
				? CacheContext.ContextType.MULTIPLAYER
				: CacheContext.ContextType.SINGLEPLAYER;
		return new CacheContext(contextType, identifier);
	}

	/** Removes cache buckets whose world/server no longer exists. */
	public int cleanOrphanContexts(java.util.Set<String> knownContexts) {
		int removed = 0;
		for (ContextStats stats : listContexts()) {
			if (!knownContexts.contains(stats.directory())) {
				deleteDir(getRootDir().resolve(stats.directory()));
				removed++;
			}
		}
		return removed;
	}

	private void deleteDir(Path dir) {
		Path root = getRootDir().toAbsolutePath().normalize();
		Path target = dir.toAbsolutePath().normalize();
		if (!target.startsWith(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(target)) {
			for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to delete cache bucket {}", target, e);
		}
	}

	/**
	 * Live view on the active bucket; every write marks the cache dirty.
	 */
	private final class ActiveCache implements TranslationCache {
		@Override
		public Optional<String> get(String original, String sourceLang, String targetLang) {
			return memory.get(original, sourceLang, targetLang);
		}

		@Override
		public void put(String original, String translated, String sourceLang, String targetLang, String textType) {
			memory.put(original, translated, sourceLang, targetLang, textType);
			dirty.set(true);
		}

		@Override
		public void save() {
			saveCurrent();
		}

		@Override
		public void load() {
			if (disk != null) {
				memory.clear();
				memory.putAll(disk.load());
			}
		}

		@Override
		public void clear() {
			clearCurrent();
		}

		@Override
		public int size() {
			return memory.size();
		}
	}
}
