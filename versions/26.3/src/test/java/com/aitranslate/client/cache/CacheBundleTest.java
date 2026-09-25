package com.aitranslate.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aitranslate.client.util.FileUtil;
import com.google.gson.JsonObject;

/**
 * The portable cache file and its merge rule (fifteenth feedback round).
 * <p>
 * The feedback document replaces the old per-world zip with one JSON file that can
 * be exported, moved around and imported again, and it fixes the merge rule: for the
 * same entry the newer one wins. Both halves are pure functions here, so the format
 * (including reading an older or hand-written file) and the rule are checked without
 * a game run.
 */
class CacheBundleTest {

	/** The map key a cache entry is stored under: the entry's own hash. */
	private static String key(String original) {
		return CacheEntry.hash(original, "auto", "zh_cn");
	}

	private static CacheEntry entry(String original, String translated, long updatedAt) {
		CacheEntry cacheEntry = new CacheEntry(original, translated, "auto", "zh_cn", "chat");
		cacheEntry.updatedAt = updatedAt;
		return cacheEntry;
	}

	@Test
	void bundleRoundTripsThroughJson() {
		CacheBundle bundle = CacheBundle.empty();
		bundle.entries("singleplayer/world a").put(key("hello"), entry("hello", "你好", 1000L));
		bundle.entries("multiplayer/127.0.0.1:25565").put(key("go north"), entry("go north", "向北", 2000L));

		JsonObject json = bundle.toJson(4242L);
		CacheBundle parsed = CacheBundle.fromJson(json, "singleplayer/fallback");

		assertEquals(2, parsed.contextNames().size());
		assertEquals(2, parsed.size());
		assertEquals("你好", parsed.entries("singleplayer/world a").get(key("hello")).translated);
		assertEquals("向北", parsed.entries("multiplayer/127.0.0.1:25565").get(key("go north")).translated);
		assertEquals(4242L, json.get("exportedAt").getAsLong());
		assertEquals(CacheBundle.FORMAT, json.get("format").getAsString());
		assertEquals(CacheBundle.VERSION, json.get("version").getAsInt());
		assertEquals(2, json.get("totalEntries").getAsInt());
	}

	@Test
	void jsonSurvivesADiskRoundTripWithoutBom() throws Exception {
		CacheBundle bundle = CacheBundle.empty();
		bundle.entries("singleplayer/world a").put(key("hello"), entry("hello", "你好", 1000L));

		java.nio.file.Path file = java.nio.file.Files.createTempFile("ai-translate-bundle", ".json");
		try {
			FileUtil.writeJson(file, bundle.toJson(1L));
			byte[] raw = java.nio.file.Files.readAllBytes(file);
			// A UTF-8 BOM would make Gson fail on the next read (a real bug of an earlier
			// round): the file has to start with '{'.
			assertEquals('{', raw[0]);
			CacheBundle parsed = CacheBundle.fromJson(FileUtil.readJson(file), "x");
			assertEquals("你好", parsed.entries("singleplayer/world a").get(key("hello")).translated);
		} finally {
			java.nio.file.Files.deleteIfExists(file);
		}
	}

	@Test
	void flatFileWithoutContextsWrapperIsRead() {
		// A single-world export (or a file the player edited by hand) carries the entries
		// at the top level; they land in the given default context.
		JsonObject root = new JsonObject();
		JsonObject entries = new JsonObject();
		entries.add(key("sign"), entry("sign", "告示牌", 5L).toJson());
		root.add("entries", entries);

		CacheBundle parsed = CacheBundle.fromJson(root, "singleplayer/renamed");

		assertEquals(1, parsed.size());
		assertNotNull(parsed.entries("singleplayer/renamed").get(key("sign")));
	}

	@Test
	void bareEntryMapIsRead() {
		// The oldest on-disk shape: {hash: entry}.
		JsonObject root = new JsonObject();
		root.add(key("sign"), entry("sign", "告示牌", 5L).toJson());

		CacheBundle parsed = CacheBundle.fromJson(root, "singleplayer/old");

		assertEquals(1, parsed.size());
		assertEquals("告示牌", parsed.entries("singleplayer/old").get(key("sign")).translated);
	}

	@Test
	void damagedEntriesAndValuesAreSkipped() {
		JsonObject root = new JsonObject();
		JsonObject entries = new JsonObject();
		entries.addProperty("broken", "not an object");
		entries.add(key("ok"), entry("ok", "好", 5L).toJson());
		root.add("entries", entries);

		CacheBundle parsed = CacheBundle.fromJson(root, "c");

		assertEquals(1, parsed.size());
		assertTrue(parsed.entries("c").containsKey(key("ok")));
	}

	@Test
	void newerImportWins() {
		Map<String, CacheEntry> target = new LinkedHashMap<>();
		CacheEntry old = entry("hello", "旧译文", 1000L);
		target.put(old.key(), old);
		Map<String, CacheEntry> incoming = Map.of(old.key(), entry("hello", "新译文", 2000L));

		CacheBundle.MergeResult result = CacheBundle.mergeInto(target, incoming);

		assertEquals(1, result.replaced());
		assertEquals(0, result.added());
		assertEquals(0, result.kept());
		assertEquals("新译文", target.get(old.key()).translated);
	}

	@Test
	void olderImportIsKept() {
		// Importing an older file must never downgrade what the player already has.
		Map<String, CacheEntry> target = new LinkedHashMap<>();
		CacheEntry current = entry("hello", "当前译文", 5000L);
		target.put(current.key(), current);
		Map<String, CacheEntry> incoming = Map.of(current.key(), entry("hello", "旧译文", 1000L));

		CacheBundle.MergeResult result = CacheBundle.mergeInto(target, incoming);

		assertEquals(0, result.replaced());
		assertEquals(1, result.kept());
		assertEquals("当前译文", target.get(current.key()).translated);
	}

	@Test
	void identicalTimestampKeepsTheExistingEntry() {
		Map<String, CacheEntry> target = new LinkedHashMap<>();
		CacheEntry existing = entry("hello", "已有", 1000L);
		target.put(existing.key(), existing);
		Map<String, CacheEntry> incoming = Map.of(existing.key(), entry("hello", "导入", 1000L));

		CacheBundle.MergeResult result = CacheBundle.mergeInto(target, incoming);

		assertEquals(1, result.kept());
		assertEquals("已有", target.get(existing.key()).translated);
	}

	@Test
	void mergeAddsUnknownEntriesAndCountsThem() {
		Map<String, CacheEntry> target = new LinkedHashMap<>();
		CacheEntry kept = entry("a", "A", 3000L);
		CacheEntry replaced = entry("b", "B-旧", 1000L);
		target.put(kept.key(), kept);
		target.put(replaced.key(), replaced);

		Map<String, CacheEntry> incoming = new LinkedHashMap<>();
		incoming.put(kept.key(), entry("a", "A-更旧", 100L));
		incoming.put(replaced.key(), entry("b", "B-新", 2000L));
		incoming.put(CacheEntry.hash("c", "auto", "zh_cn"), entry("c", "C", 10L));

		CacheBundle.MergeResult result = CacheBundle.mergeInto(target, incoming);

		assertEquals(1, result.added());
		assertEquals(1, result.replaced());
		assertEquals(1, result.kept());
		assertEquals(3, result.total());
		assertEquals(3, target.size());
		assertTrue(result.describe().contains("新增 1 条"));
	}

	@Test
	void emptyBundleIsRecognised() {
		CacheBundle bundle = CacheBundle.empty();
		assertEquals(0, bundle.size());
		assertFalse(CacheBundle.fromJson(null, "c").size() > 0);
		assertFalse(CacheBundle.fromJson(new com.google.gson.JsonArray(), "c").size() > 0);
	}
}
