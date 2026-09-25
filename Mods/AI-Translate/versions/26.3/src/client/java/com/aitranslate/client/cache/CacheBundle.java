package com.aitranslate.client.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The portable form of the cache: <em>all</em> worlds of one installation in a single
 * JSON file (fifteenth feedback round).
 * <p>
 * The feedback document replaces the old "one zip per world, imported from a fixed
 * folder" scheme with real files: 导出 writes one JSON file holding every world's
 * cache, 导入 asks for a JSON file and <em>merges</em> it - for the same key the
 * newer entry wins, and nothing else is touched. That is why this class exists
 * separately from the disk layout:
 *
 * <ul>
 * <li>serialising and parsing are pure functions of a {@code JsonObject}, so the
 * format and the merge rule are covered by unit tests instead of by a game run;</li>
 * <li>both the old zip layout and a hand-written file stay readable, because parsing
 * is tolerant: a missing {@code contexts} wrapper, a missing {@code entries}
 * wrapper and a bare {@code {hash: entry}} map are all accepted.</li>
 * </ul>
 *
 * File shape:
 * <pre>
 * {
 *   "format": "ai-translate-cache",
 *   "version": 1,
 *   "exportedAt": 1737000000000,
 *   "contexts": {
 *     "singleplayer/my world": { "entries": { "&lt;hash&gt;": { "original": …, "translated": … } } }
 *   }
 * }
 * </pre>
 */
public final class CacheBundle {

	/** Marker written to (and checked in) exported files. */
	public static final String FORMAT = "ai-translate-cache";
	public static final int VERSION = 1;

	/** One world's entries, keyed by cache hash. */
	private final Map<String, Map<String, CacheEntry>> contexts = new LinkedHashMap<>();

	private CacheBundle() {
	}

	public static CacheBundle empty() {
		return new CacheBundle();
	}

	/** The context directory names in this bundle, in insertion order. */
	public List<String> contextNames() {
		return new ArrayList<>(contexts.keySet());
	}

	/** The entries of one context (never {@code null}; empty for an unknown name). */
	public Map<String, CacheEntry> entries(String context) {
		return contexts.computeIfAbsent(context, key -> new LinkedHashMap<>());
	}

	/** Total number of entries in the bundle. */
	public int size() {
		int total = 0;
		for (Map<String, CacheEntry> entries : contexts.values()) {
			total += entries.size();
		}
		return total;
	}


	// ------------------------------------------------------------- writing

	public JsonObject toJson(long exportedAt) {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("version", VERSION);
		root.addProperty("exportedAt", exportedAt);
		JsonObject contextsJson = new JsonObject();
		for (Map.Entry<String, Map<String, CacheEntry>> context : contexts.entrySet()) {
			JsonObject entriesJson = new JsonObject();
			for (Map.Entry<String, CacheEntry> entry : context.getValue().entrySet()) {
				entriesJson.add(entry.getKey(), entry.getValue().toJson());
			}
			JsonObject wrapper = new JsonObject();
			wrapper.add("entries", entriesJson);
			wrapper.addProperty("count", entriesJson.size());
			contextsJson.add(context.getKey(), wrapper);
		}
		root.add("contexts", contextsJson);
		root.addProperty("totalEntries", size());
		return root;
	}

	// ------------------------------------------------------------- reading

	/**
	 * Reads a bundle, in either the exported shape or a bare
	 * {@code {context: {hash: entry}}} map.
	 *
	 * @param defaultContext context to use when the file is a flat map of entries
	 *                       (single world export, hand-written file)
	 */
	public static CacheBundle fromJson(JsonElement root, String defaultContext) {
		CacheBundle bundle = new CacheBundle();
		if (root == null || !root.isJsonObject()) {
			return bundle;
		}
		JsonObject object = root.getAsJsonObject();
		JsonObject contexts = object.has("contexts") && object.get("contexts").isJsonObject()
				? object.getAsJsonObject("contexts")
				: null;
		if (contexts != null) {
			for (Map.Entry<String, JsonElement> context : contexts.entrySet()) {
				if (!context.getValue().isJsonObject()) {
					continue;
				}
				readEntries(context.getValue().getAsJsonObject(), bundle.entries(context.getKey()));
			}
			return bundle;
		}
		// No contexts wrapper: either one world's file ({"entries": {...}}) or a bare map.
		readEntries(object, bundle.entries(defaultContext == null ? "" : defaultContext));
		return bundle;
	}

	private static void readEntries(JsonObject wrapper, Map<String, CacheEntry> target) {
		JsonObject map = wrapper.has("entries") && wrapper.get("entries").isJsonObject()
				? wrapper.getAsJsonObject("entries")
				: wrapper;
		for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
			if (!entry.getValue().isJsonObject()) {
				continue;
			}
			try {
				CacheEntry parsed = CacheEntry.fromJson(entry.getValue().getAsJsonObject());
				target.put(parsed.key(), parsed);
			} catch (RuntimeException ignored) {
				// One damaged entry never costs the whole file.
			}
		}
	}

	// -------------------------------------------------------------- merging

	/** What a merge did, for the report the player sees. */
	public record MergeResult(int added, int replaced, int kept) {
		public int total() {
			return added + replaced + kept;
		}

		public String describe() {
			return "新增 " + added + " 条，更新 " + replaced + " 条，保留 " + kept + " 条";
		}
	}

	/**
	 * Merges {@code incoming} into {@code target} and reports what happened.
	 * <p>
	 * The rule from the feedback document: "same key ⇒ newer wins". An entry that is
	 * already there and is at least as new as the imported one is kept, so importing
	 * an older file can never downgrade a translation the player already has.
	 *
	 * @return counts of what the merge did (added / replaced / kept)
	 */
	public static MergeResult mergeInto(Map<String, CacheEntry> target, Map<String, CacheEntry> incoming) {
		int added = 0;
		int replaced = 0;
		int kept = 0;
		for (Map.Entry<String, CacheEntry> entry : incoming.entrySet()) {
			String key = entry.getKey();
			CacheEntry candidate = entry.getValue();
			CacheEntry existing = target.get(key);
			if (existing == null) {
				target.put(key, candidate);
				added++;
			} else if (candidate.updatedAt > existing.updatedAt) {
				target.put(key, candidate);
				replaced++;
			} else {
				kept++;
			}
		}
		return new MergeResult(added, replaced, kept);
	}
}
