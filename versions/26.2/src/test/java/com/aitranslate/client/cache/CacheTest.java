package com.aitranslate.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CacheTest {

	@Test
	void hashIsStableAndLanguageAware() {
		String hash = CacheEntry.hash("Hello", "auto", "zh_cn");
		assertEquals(hash, CacheEntry.hash("Hello", "auto", "zh_cn"));
		assertNotEquals(hash, CacheEntry.hash("Hello", "auto", "en_us"));
		assertNotEquals(hash, CacheEntry.hash("Hello!", "auto", "zh_cn"));
		assertEquals(32, hash.length());
	}

	@Test
	void entryRoundTripsThroughJson() {
		CacheEntry entry = new CacheEntry("Hello", "你好", "auto", "zh_cn", "chat");
		CacheEntry parsed = CacheEntry.fromJson(entry.toJson());
		assertEquals(entry.key(), parsed.key());
		assertEquals("Hello", parsed.original);
		assertEquals("你好", parsed.translated);
		assertEquals("chat", parsed.textType);
	}

	@Test
	void memoryCacheStoresAndReturns() {
		MemoryCache cache = new MemoryCache();
		cache.put("Hello", "你好", "auto", "zh_cn", "chat");
		Optional<String> hit = cache.get("Hello", "auto", "zh_cn");
		assertTrue(hit.isPresent());
		assertEquals("你好", hit.get());
		assertTrue(cache.get("Missing", "auto", "zh_cn").isEmpty());
		assertEquals(1, cache.size());
		assertEquals(1L, cache.hits());
		assertEquals(1L, cache.misses());
		cache.clear();
		assertEquals(0, cache.size());
	}

	@Test
	void contextDirectoryIsSanitised() {
		CacheContext single = CacheContext.singleplayer("为时已晚");
		assertEquals("singleplayer/为时已晚", single.directoryName());
		CacheContext server = CacheContext.multiplayer("play.example.com:25565");
		assertEquals("multiplayer/play.example.com_25565", server.directoryName());
	}

	@Test
	void sameNamedWorldsInDifferentSaveFoldersUseDifferentBuckets() {
		CacheContext first = CacheContext.singleplayer(Path.of("saves", "world-one"), "Shared name");
		CacheContext second = CacheContext.singleplayer(Path.of("saves", "world-two"), "Shared name");

		assertEquals("singleplayer/world-one", first.directoryName());
		assertEquals("singleplayer/world-two", second.directoryName());
		assertNotEquals(first, second);
	}

	@Test
	void textTypeFilesFollowTheSpecification() {
		assertEquals("chat", com.aitranslate.client.capture.TextType.CHAT.fileName());
		assertEquals("entity_name", com.aitranslate.client.capture.TextType.ENTITY_NAME.fileName());
		assertEquals("dialog", com.aitranslate.client.capture.TextType.DIALOG.fileName());
	}
}
