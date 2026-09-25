package com.aitranslate.client.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** The in-memory index must stay bounded (LRU) so long sessions do not leak. */
class MemoryCacheLruTest {

	@Test
	void evictsLeastRecentlyUsedEntries() {
		MemoryCache cache = new MemoryCache(() -> 8);
		for (int i = 0; i < 8; i++) {
			cache.put("text " + i, "译文 " + i, "auto", "zh_cn", "chat");
		}
		assertEquals(8, cache.size());

		// touch the oldest entry so it becomes the most recently used
		assertTrue(cache.get("text 0", "auto", "zh_cn").isPresent());
		cache.put("text 8", "译文 8", "auto", "zh_cn", "chat");

		assertEquals(8, cache.size());
		assertTrue(cache.get("text 0", "auto", "zh_cn").isPresent(), "recently used entry must survive");
		assertTrue(cache.get("text 1", "auto", "zh_cn").isEmpty(), "least recently used entry must be evicted");
	}

	@Test
	void respectsAShrinkingLimit() {
		AtomicInteger limit = new AtomicInteger(40);
		MemoryCache cache = new MemoryCache(limit::get);
		for (int i = 0; i < 40; i++) {
			cache.put("text " + i, "译文 " + i, "auto", "zh_cn", "chat");
		}
		assertEquals(40, cache.size());

		limit.set(8);
		// Every insert evicts at most one entry, so the map shrinks towards the new
		// limit as entries are added.
		for (int i = 100; i < 140; i++) {
			cache.put("text " + i, "译文 " + i, "auto", "zh_cn", "chat");
		}
		assertEquals(8, cache.size(), "the new limit must be applied on the next inserts");
	}

	@Test
	void tracksHitRate() {
		MemoryCache cache = new MemoryCache(() -> 100);
		cache.put("a", "甲", "auto", "zh_cn", "chat");
		cache.get("a", "auto", "zh_cn");
		cache.get("a", "auto", "zh_cn");
		cache.get("missing", "auto", "zh_cn");
		assertEquals(2L, cache.hits());
		assertEquals(1L, cache.misses());
		assertEquals(66L, cache.hitRatePercent());
	}

	@Test
	void ignoresBlankTranslations() {
		MemoryCache cache = new MemoryCache(() -> 100);
		cache.put("a", "   ", "auto", "zh_cn", "chat");
		assertEquals(0, cache.size());
	}
}
