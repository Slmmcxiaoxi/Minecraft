package com.aitranslate.client.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.util.FileUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Disk backend of the cache: {@code <root>/<context>/<textType>.json} plus a
 * {@code metadata.json} describing the context.
 */
public class DiskCache {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	private final Path directory;
	private final CacheContext context;

	public DiskCache(Path directory, CacheContext context) {
		this.directory = directory;
		this.context = context;
	}

	public Path directory() {
		return directory;
	}

	/** Reads every {@code *.json} file of this context into memory. */
	public List<CacheEntry> load() {
		List<CacheEntry> entries = new ArrayList<>();
		try {
			// Creating the empty bucket on first world entry makes the configured cache
			// location predictable even when no API key is configured and no translation
			// has been written yet.
			Files.createDirectories(directory);
		} catch (IOException e) {
			LOGGER.warn("Failed to create cache directory {}", directory, e);
			return entries;
		}
		try (Stream<Path> files = Files.list(directory)) {
			for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
				String name = file.getFileName().toString();
				if ("metadata.json".equals(name)) {
					continue;
				}
				entries.addAll(readFile(file));
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to read cache directory {}", directory, e);
		}
		return entries;
	}

	private List<CacheEntry> readFile(Path file) {
		List<CacheEntry> entries = new ArrayList<>();
		try {
			JsonElement root = FileUtil.readJson(file);
			if (!root.isJsonObject()) {
				return entries;
			}
			JsonObject object = root.getAsJsonObject();
			// Entries are stored either as {"entries": {...}} or as a bare map.
			JsonObject map = object.has("entries") && object.get("entries").isJsonObject()
					? object.getAsJsonObject("entries")
					: object;
			for (Map.Entry<String, JsonElement> e : map.entrySet()) {
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				try {
					entries.add(CacheEntry.fromJson(e.getValue().getAsJsonObject()));
				} catch (RuntimeException ignored) {
					// Skip malformed single entries instead of losing the whole file.
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to parse cache file {}", file, e);
		}
		return entries;
	}

	/**
	 * Writes the given entries grouped by their text type.
	 * <p>
	 * The grouping key is the <em>file name</em>, not the enum constant: two text types
	 * that share a file name would otherwise write two files with the same name, and the
	 * second one would wipe out the first (which is exactly what happened to
	 * {@code TITLE} and {@code ACTION_BAR} until the fifteenth feedback round - both used
	 * {@code title.json}, so one of them was silently missing from every cache file).
	 * Grouping by file name keeps that from happening even if a future type is added with
	 * a name that is already taken.
	 */
	public void save(Iterable<CacheEntry> entries) {
		Map<String, JsonObject> grouped = new java.util.LinkedHashMap<>();
		int total = 0;
		long lastUpdated = 0L;
		for (CacheEntry entry : entries) {
			TextType type = typeOf(entry.textType);
			JsonObject map = grouped.computeIfAbsent(type.fileName(), t -> new JsonObject());
			map.add(entry.key(), entry.toJson());
			total++;
			lastUpdated = Math.max(lastUpdated, entry.updatedAt);
		}

		try {
			Files.createDirectories(directory);
			for (Map.Entry<String, JsonObject> e : grouped.entrySet()) {
				JsonObject file = new JsonObject();
				file.add("entries", e.getValue());
				file.addProperty("file", e.getKey());
				file.addProperty("savedAt", System.currentTimeMillis());
				FileUtil.writeJson(directory.resolve(e.getKey() + ".json"), file);
			}
			writeMetadata(total, lastUpdated);
		} catch (IOException e) {
			LOGGER.error("Failed to persist cache for context {}", context, e);
		}
	}

	public void writeMetadata(int totalEntries, long lastUpdatedAt) {
		Path metadata = directory.resolve("metadata.json");
		try {
			JsonObject json = new JsonObject();
			json.addProperty("worldName", context.identifier());
			json.addProperty("worldType", context.type().name().toLowerCase(Locale.ROOT));
			json.addProperty("firstSeenAt", firstSeenAt(metadata));
			json.addProperty("lastUpdatedAt", lastUpdatedAt > 0 ? lastUpdatedAt : System.currentTimeMillis());
			json.addProperty("totalEntries", totalEntries);
			json.addProperty("sourceLang", "auto");
			json.addProperty("targetLang", "zh_cn");
			FileUtil.writeJson(metadata, json);
		} catch (IOException e) {
			LOGGER.warn("Failed to write cache metadata {}", metadata, e);
		}
	}

	private long firstSeenAt(Path metadata) {
		if (Files.isRegularFile(metadata)) {
			try {
				JsonElement root = FileUtil.readJson(metadata);
				if (root.isJsonObject() && root.getAsJsonObject().has("firstSeenAt")) {
					return root.getAsJsonObject().get("firstSeenAt").getAsLong();
				}
			} catch (IOException | RuntimeException ignored) {
				// fall through to "now"
			}
		}
		return System.currentTimeMillis();
	}

	public static TextType typeOf(String fileName) {
		if (fileName == null) {
			return TextType.CHAT;
		}
		String needle = fileName.toLowerCase(Locale.ROOT);
		for (TextType type : TextType.values()) {
			if (type.name().equalsIgnoreCase(needle) || type.fileName().equals(needle)) {
				return type;
			}
		}
		return TextType.CHAT;
	}
}
