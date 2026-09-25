package com.aitranslate.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.util.FileUtil;

/**
 * The on-disk cache layout.
 * <p>
 * Fifteenth feedback round: the export probe reported "92 entries in memory, 90 in the
 * file". The cause was not the export at all - {@link TextType#TITLE} and
 * {@link TextType#ACTION_BAR} shared the file name {@code title}, so the per-type
 * writer created two files with the same name and the second one wiped out the first.
 * Every title or action bar translation of a session was therefore missing from the
 * cache on disk, from every export and from every import.
 * <p>
 * These tests pin the two things that have to stay true: the file names are unique,
 * and a bucket round-trips completely through disk.
 */
class DiskCacheLayoutTest {
	@Test
	void loadingAnEmptyBucketCreatesItsDirectory() throws IOException {
		Path root = Files.createTempDirectory("ai-translate-empty-cache");
		Path bucket = root.resolve("singleplayer").resolve("new-world");
		DiskCache cache = new DiskCache(bucket, CacheContext.singleplayer("new-world"));

		assertTrue(cache.load().isEmpty());
		assertTrue(Files.isDirectory(bucket));
	}

	@Test
	void everyTextTypeHasItsOwnCacheFile() {
		Map<String, TextType> byName = new HashMap<>();
		for (TextType type : TextType.values()) {
			TextType clash = byName.put(type.fileName(), type);
			assertTrue(clash == null, "TextType." + type + " reuses the cache file of " + clash + ": "
					+ type.fileName() + ".json");
		}
		assertEquals(TextType.values().length, byName.size());
	}

	@Test
	void fileNamesAreResolvableBackToTheirType() {
		for (TextType type : TextType.values()) {
			assertEquals(type, DiskCache.typeOf(type.fileName()),
					"the cache file " + type.fileName() + ".json must load back as " + type);
			assertEquals(type, DiskCache.typeOf(type.name()));
		}
	}

	@Test
	void bucketRoundTripsThroughDiskWithoutLosingEntries() throws Exception {
		Path dir = Files.createTempDirectory("ai-translate-disc-cache");
		try {
			CacheContext context = CacheContext.singleplayer("layout test");
			DiskCache disk = new DiskCache(dir, context);
			List<CacheEntry> written = new ArrayList<>();
			// One entry per text source, including the two that used to collide.
			for (TextType type : TextType.values()) {
				CacheEntry entry = new CacheEntry("text for " + type.name(), "译文 " + type.name(), "auto", "zh_cn",
						type.name().toLowerCase(java.util.Locale.ROOT));
				written.add(entry);
			}
			disk.save(written);

			List<CacheEntry> loaded = disk.load();
			assertEquals(written.size(), loaded.size(), "every entry must survive a save/load round trip");
			List<String> originals = loaded.stream().map(entry -> entry.original).toList();
			assertTrue(originals.contains("text for TITLE"), "the title entry must be on disk");
			assertTrue(originals.contains("text for ACTION_BAR"), "the action bar entry must be on disk");

			// And the files themselves are per source (no shared name).
			try (var files = Files.list(dir)) {
				long jsonFiles = files.filter(path -> path.getFileName().toString().endsWith(".json")).count();
				assertTrue(jsonFiles >= TextType.values().length,
						"expected one cache file per text source plus metadata, found " + jsonFiles);
			}
		} finally {
			try (var walk = Files.walk(dir)) {
				for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	@Test
	void entryTypeIsPreservedAcrossDisk() throws Exception {
		Path dir = Files.createTempDirectory("ai-translate-disc-cache");
		try {
			DiskCache disk = new DiskCache(dir, CacheContext.singleplayer("type test"));
			CacheEntry title = new CacheEntry("Chapter One", "第一章", "auto", "zh_cn", "title");
			CacheEntry actionBar = new CacheEntry("Press W to move", "按 W 移动", "auto", "zh_cn", "action_bar");
			disk.save(List.of(title, actionBar));

			Map<String, String> types = new HashMap<>();
			for (CacheEntry entry : disk.load()) {
				types.put(entry.original, entry.textType);
			}
			assertEquals("title", types.get("Chapter One"));
			assertEquals("action_bar", types.get("Press W to move"));
			// The action bar file exists separately now.
			assertTrue(Files.isRegularFile(dir.resolve(TextType.ACTION_BAR.fileName() + ".json")));
			assertTrue(FileUtil.directorySize(dir) > 0);
		} finally {
			try (var walk = Files.walk(dir)) {
				for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}
}
