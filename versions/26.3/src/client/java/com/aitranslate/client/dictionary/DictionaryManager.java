package com.aitranslate.client.dictionary;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.cache.CacheContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Persistent global and per-world fixed translations. */
public final class DictionaryManager {
	public enum Scope { CURRENT, GLOBAL }

	private static final Logger LOGGER = AITranslateMod.LOGGER;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final java.util.function.Supplier<Path> root;
	private final java.util.function.BooleanSupplier ignoreCase;
	private volatile CacheContext context;
	private volatile List<DictionaryEntry> current = List.of();
	private volatile List<DictionaryEntry> global = List.of();

	public DictionaryManager(java.util.function.Supplier<Path> root) {
		this(root, () -> false);
	}

	public DictionaryManager(java.util.function.Supplier<Path> root, java.util.function.BooleanSupplier ignoreCase) {
		this.root = root;
		this.ignoreCase = ignoreCase == null ? () -> false : ignoreCase;
		this.global = load(path(Scope.GLOBAL));
	}

	public synchronized void switchContext(CacheContext next) {
		context = next;
		current = next == null ? List.of() : load(path(Scope.CURRENT));
		LOGGER.info("AI Translate dictionaries loaded: current={}, global={}, context={}", current.size(), global.size(),
				next);
	}

	public Optional<String> lookup(String original, String sourceLang, String targetLang, boolean ignoreCase) {
		if (original == null || original.isEmpty()) return Optional.empty();
		Optional<String> local = lookup(current, original, sourceLang, targetLang, ignoreCase);
		return local.isPresent() ? local : lookup(global, original, sourceLang, targetLang, ignoreCase);
	}

	private static Optional<String> lookup(List<DictionaryEntry> entries, String original, String source,
			String target, boolean ignoreCase) {
		String src = language(source, "auto");
		String dst = language(target, "zh_cn");
		DictionaryEntry fallback = null;
		for (DictionaryEntry entry : entries) {
			boolean text = ignoreCase ? entry.original().equalsIgnoreCase(original) : entry.original().equals(original);
			if (!text || !entry.targetLang().equals(dst)) continue;
			if (entry.sourceLang().equals(src)) return Optional.of(entry.translated());
			if (src.equals("auto") && fallback == null) fallback = entry;
		}
		return Optional.ofNullable(fallback == null ? null : fallback.translated());
	}

	public List<DictionaryEntry> entries(Scope scope) {
		return List.copyOf(scope == Scope.CURRENT ? current : global);
	}

	public boolean hasCurrentContext() { return context != null; }

	public record ContextStats(String directory, String name, String type, int entries, long size, long lastUpdated) {
	}

	/** Stored per-world dictionaries, including the active one when a world is open. */
	public List<ContextStats> listContexts() {
		List<ContextStats> stats = new ArrayList<>();
		Path baseRoot = root.get();
		for (String type : List.of("singleplayer", "multiplayer")) {
			Path base = baseRoot.resolve(type);
			if (!Files.isDirectory(base)) continue;
			try (var directories = Files.list(base)) {
				for (Path directory : directories.filter(Files::isDirectory).toList()) {
					Path file = directory.resolve("dictionary.json");
					if (!Files.isRegularFile(file)) continue;
					long size = 0L;
					long modified = 0L;
					try {
						size = Files.size(file);
						modified = Files.getLastModifiedTime(file).toMillis();
					} catch (IOException ignored) {
						// The entry can still be listed and managed.
					}
					stats.add(new ContextStats(type + "/" + directory.getFileName(), directory.getFileName().toString(),
							type, load(file).size(), size, modified));
				}
			} catch (IOException e) {
				LOGGER.warn("Could not list dictionaries under {}", base, e);
			}
		}
		stats.sort((a, b) -> Long.compare(b.lastUpdated(), a.lastUpdated()));
		return List.copyOf(stats);
	}

	public boolean isCurrent(String directory) {
		CacheContext active = context;
		return active != null && directory != null && directory.equals(active.directoryName());
	}

	public List<DictionaryEntry> entries(String directory) {
		Path file = contextFile(directory);
		return file == null ? List.of() : load(file);
	}

	public synchronized int putAll(String directory, List<DictionaryEntry> values) {
		Path file = contextFile(directory);
		if (file == null || values == null || values.isEmpty()) return 0;
		List<DictionaryEntry> changed = new ArrayList<>(load(file));
		int accepted = 0;
		for (DictionaryEntry value : values) {
			DictionaryEntry entry = value == null ? null : value.normalized();
			if (entry == null || !entry.valid()) continue;
			changed.removeIf(old -> sameKey(old, entry, ignoreCase.getAsBoolean()));
			changed.add(entry);
			accepted++;
		}
		changed.sort(Comparator.comparing(DictionaryEntry::original, String.CASE_INSENSITIVE_ORDER));
		write(file, changed);
		refreshAfterChange();
		return accepted;
	}

	public synchronized void remove(String directory, DictionaryEntry entry) {
		Path file = contextFile(directory);
		if (file == null || entry == null) return;
		List<DictionaryEntry> changed = new ArrayList<>(load(file));
		changed.removeIf(old -> sameKey(old, entry, ignoreCase.getAsBoolean()));
		write(file, changed);
		refreshAfterChange();
	}

	public synchronized int importFile(String directory, Path source) {
		return putAll(directory, load(source));
	}

	private Path contextFile(String directory) {
		if (directory == null || directory.isBlank()) return null;
		Path base = root.get().toAbsolutePath().normalize();
		Path file = base.resolve(directory).normalize().resolve("dictionary.json");
		return file.startsWith(base) ? file : null;
	}

	private void refreshAfterChange() {
		if (context != null) current = load(path(Scope.CURRENT));
		com.aitranslate.client.display.TranslationSupport.bumpGeneration();
		com.aitranslate.client.display.TranslationSupport.clearRenderMemo();
		com.aitranslate.client.AITranslateModClient.refreshNow();
	}

	public synchronized void deleteContext(String directory) {
		if (directory == null || directory.isBlank() || isCurrent(directory)) return;
		Path base = root.get().toAbsolutePath().normalize();
		Path file = base.resolve(directory).normalize().resolve("dictionary.json");
		if (!file.startsWith(base)) return;
		try {
			Files.deleteIfExists(file);
			// A dictionary context owns only dictionary.json. Remove its now-empty
			// directory too so a disk scan cannot leave a ghost world behind.
			try { Files.deleteIfExists(file.getParent()); } catch (java.nio.file.DirectoryNotEmptyException ignored) { }
			com.aitranslate.client.display.TranslationSupport.bumpGeneration();
		} catch (IOException e) {
			LOGGER.warn("Could not delete dictionary {}", file, e);
		}
	}

	public synchronized Path exportContext(String directory) {
		Path base = root.get().toAbsolutePath().normalize();
		Path source = base.resolve(directory == null ? "" : directory).normalize().resolve("dictionary.json");
		Path target = base.resolve("exports").resolve("dictionary-"
				+ com.aitranslate.client.util.FileUtil.sanitize(directory == null ? "unknown" : directory) + ".json");
		if (!source.startsWith(base) || !Files.isRegularFile(source)) return target;
		try {
			Files.createDirectories(target.getParent());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOGGER.warn("Could not export dictionary {}", source, e);
		}
		return target;
	}

	public synchronized void put(Scope scope, DictionaryEntry value) {
		putAll(scope, value == null ? List.of() : List.of(value));
	}

	/** Installs a batch with one disk write and one render refresh. */
	public synchronized int putAll(Scope scope, List<DictionaryEntry> values) {
		if (values == null || values.isEmpty() || scope == Scope.CURRENT && context == null) return 0;
		List<DictionaryEntry> changed = new ArrayList<>(entries(scope));
		int accepted = 0;
		for (DictionaryEntry value : values) {
			DictionaryEntry entry = value == null ? null : value.normalized();
			if (entry == null || !entry.valid()) continue;
			changed.removeIf(old -> sameKey(old, entry, ignoreCase.getAsBoolean()));
			changed.add(entry);
			accepted++;
		}
		if (accepted == 0) return 0;
		changed.sort(Comparator.comparing(DictionaryEntry::original, String.CASE_INSENSITIVE_ORDER));
		install(scope, changed);
		return accepted;
	}

	public synchronized void remove(Scope scope, DictionaryEntry entry) {
		if (entry == null) return;
		List<DictionaryEntry> changed = new ArrayList<>(entries(scope));
		changed.removeIf(old -> sameKey(old, entry));
		install(scope, changed);
	}

	public synchronized void clear(Scope scope) { install(scope, List.of()); }

	public synchronized int importFile(Scope scope, Path file) {
		if (scope == Scope.CURRENT && context == null) return 0;
		List<DictionaryEntry> imported = load(file);
		Map<String, DictionaryEntry> merged = new LinkedHashMap<>();
		for (DictionaryEntry entry : entries(scope)) merged.put(key(entry, ignoreCase.getAsBoolean()), entry);
		for (DictionaryEntry entry : imported) merged.put(key(entry, ignoreCase.getAsBoolean()), entry);
		install(scope, new ArrayList<>(merged.values()));
		return imported.size();
	}

	public synchronized Path export(Scope scope) {
		Path dir = root.get().resolve("exports");
		String name = scope == Scope.CURRENT ? "dictionary-current.json" : "dictionary-global.json";
		Path target = dir.resolve(name);
		write(target, entries(scope));
		return target;
	}

	public Path importStartDirectory() { return root.get(); }
	public Path rootDir() { return root.get(); }

	public synchronized void reload() {
		global = load(path(Scope.GLOBAL));
		current = context == null ? List.of() : load(path(Scope.CURRENT));
	}

	/** Deletes dictionary.json files only; translation cache files are untouched. */
	public synchronized int clearAll() {
		int removed = 0;
		Path directory = root.get();
		if (Files.isDirectory(directory)) {
			try (var paths = Files.walk(directory)) {
				for (Path file : paths.filter(Files::isRegularFile)
						.filter(file -> file.getFileName().toString().equalsIgnoreCase("dictionary.json")).toList()) {
					if (Files.deleteIfExists(file)) removed++;
				}
			} catch (IOException e) {
				LOGGER.warn("Could not clear all dictionaries under {}", directory, e);
			}
		}
		global = List.of(); current = List.of();
		com.aitranslate.client.display.TranslationSupport.bumpGeneration();
		com.aitranslate.client.display.TranslationSupport.clearRenderMemo();
		com.aitranslate.client.AITranslateModClient.refreshNow();
		return removed;
	}

	private void install(Scope scope, List<DictionaryEntry> values) {
		if (scope == Scope.CURRENT && context == null) return;
		List<DictionaryEntry> immutable = List.copyOf(values);
		if (scope == Scope.CURRENT) current = immutable; else global = immutable;
		write(path(scope), immutable);
		com.aitranslate.client.display.TranslationSupport.bumpGeneration();
		com.aitranslate.client.display.TranslationSupport.clearRenderMemo();
		com.aitranslate.client.AITranslateModClient.refreshNow();
	}

	private Path path(Scope scope) {
		if (scope == Scope.GLOBAL) return root.get().resolve("global").resolve("dictionary.json");
		CacheContext active = context;
		return active == null ? root.get().resolve("inactive").resolve("dictionary.json")
				: root.get().resolve(active.directoryName()).resolve("dictionary.json");
	}

	private static List<DictionaryEntry> load(Path file) {
		if (file == null || !Files.isRegularFile(file)) return List.of();
		List<DictionaryEntry> entries = new ArrayList<>();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement parsed = JsonParser.parseReader(reader);
			JsonArray array = parsed.isJsonArray() ? parsed.getAsJsonArray()
					: parsed.isJsonObject() && parsed.getAsJsonObject().has("entries")
							? parsed.getAsJsonObject().getAsJsonArray("entries") : new JsonArray();
			for (JsonElement element : array) {
				if (!element.isJsonObject()) continue;
				JsonObject object = element.getAsJsonObject();
				DictionaryEntry entry = new DictionaryEntry(string(object, "original"), string(object, "translated"),
						string(object, "sourceLang"), string(object, "targetLang")).normalized();
				if (entry.valid()) entries.add(entry);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not load dictionary {}", file, e);
		}
		return List.copyOf(entries);
	}

	private static void write(Path file, List<DictionaryEntry> entries) {
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("formatVersion", 1);
			JsonArray array = new JsonArray();
			for (DictionaryEntry entry : entries) array.add(GSON.toJsonTree(entry));
			root.add("entries", array);
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { GSON.toJson(root, writer); }
			try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
			catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.warn("Could not save dictionary {}", file, e);
		}
	}

	private static String string(JsonObject object, String name) {
		return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
	}

	private static boolean sameKey(DictionaryEntry a, DictionaryEntry b) { return sameKey(a, b, false); }
	private static boolean sameKey(DictionaryEntry a, DictionaryEntry b, boolean ignoreCase) {
		return key(a, ignoreCase).equals(key(b, ignoreCase));
	}
	private static String key(DictionaryEntry e) {
		return key(e, false);
	}
	private static String key(DictionaryEntry e, boolean ignoreCase) {
		String original = ignoreCase ? e.original().toLowerCase(Locale.ROOT) : e.original();
		return original + '\0' + e.sourceLang() + '\0' + e.targetLang();
	}
	private static String language(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
	}
}
