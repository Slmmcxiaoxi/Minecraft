package com.aitranslate.client.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aitranslate.client.cache.CacheContext;
import com.aitranslate.client.dictionary.DictionaryManager.Scope;

class DictionaryManagerTest {
	@TempDir Path root;

	@Test
	void currentOverridesGlobalAndWorldsAreIsolated() {
		DictionaryManager manager = new DictionaryManager(() -> root);
		manager.put(Scope.GLOBAL, new DictionaryEntry("Hello", "全局你好", "en_us", "zh_cn"));
		manager.switchContext(CacheContext.singleplayer("world_a"));
		manager.put(Scope.CURRENT, new DictionaryEntry("Hello", "本地你好", "en_us", "zh_cn"));

		assertEquals("本地你好", manager.lookup("Hello", "en_us", "zh_cn", false).orElseThrow());
		assertEquals("本地你好", manager.lookup("Hello", "auto", "zh_cn", false).orElseThrow());

		manager.switchContext(CacheContext.singleplayer("world_b"));
		assertEquals("全局你好", manager.lookup("Hello", "en_us", "zh_cn", false).orElseThrow());
		manager.switchContext(CacheContext.singleplayer("world_a"));
		assertEquals("本地你好", manager.lookup("Hello", "en_us", "zh_cn", false).orElseThrow());
	}

	@Test
	void matchingHonoursLanguageAndOptionalCaseFolding() {
		DictionaryManager manager = new DictionaryManager(() -> root);
		manager.put(Scope.GLOBAL, new DictionaryEntry("Goodbye", "再见", "en_us", "zh_cn"));
		assertTrue(manager.lookup("goodbye", "en_us", "zh_cn", false).isEmpty());
		assertEquals("再见", manager.lookup("goodbye", "en_us", "zh_cn", true).orElseThrow());
		assertTrue(manager.lookup("Goodbye", "en_us", "ja_jp", false).isEmpty());
	}

	@Test
	void defaultCaseInsensitiveStoreTreatsHelloAndLowercaseAsOneEntry() {
		DictionaryManager manager = new DictionaryManager(() -> root, () -> true);
		manager.put(Scope.GLOBAL, new DictionaryEntry("Hello", "你好", "en_us", "zh_cn"));
		manager.put(Scope.GLOBAL, new DictionaryEntry("hello", "您好", "en_us", "zh_cn"));
		assertEquals(1, manager.entries(Scope.GLOBAL).size());
		assertEquals("您好", manager.lookup("HELLO", "en_us", "zh_cn", true).orElseThrow());
	}

	@Test
	void batchInsertOverwritesConflictsAndWritesAllEntries() {
		DictionaryManager manager = new DictionaryManager(() -> root, () -> true);
		assertEquals(3, manager.putAll(Scope.GLOBAL, java.util.List.of(
				new DictionaryEntry("Hello", "闹吃", "en_us", "zh_cn"),
				new DictionaryEntry("Byebye", "闹吃", "en_us", "zh_cn"),
				new DictionaryEntry("hello", "覆盖", "en_us", "zh_cn"))));
		assertEquals(2, manager.entries(Scope.GLOBAL).size());
		assertEquals("覆盖", manager.lookup("HELLO", "en_us", "zh_cn", true).orElseThrow());
	}

	@Test
	void exportAndImportUsePortableJson() {
		DictionaryManager first = new DictionaryManager(() -> root.resolve("one"));
		first.put(Scope.GLOBAL, new DictionaryEntry("Water", "水", "en_us", "zh_cn"));
		Path exported = first.export(Scope.GLOBAL);

		DictionaryManager second = new DictionaryManager(() -> root.resolve("two"));
		assertEquals(1, second.importFile(Scope.GLOBAL, exported));
		assertEquals("水", second.lookup("Water", "en_us", "zh_cn", false).orElseThrow());
	}

	@Test
	void storedContextListIncludesWorldsAndProtectsTheActiveDictionary() {
		DictionaryManager manager = new DictionaryManager(() -> root);
		manager.switchContext(CacheContext.singleplayer("world_a"));
		manager.put(Scope.CURRENT, new DictionaryEntry("Hello", "你好", "en_us", "zh_cn"));
		manager.switchContext(CacheContext.multiplayer("example.test"));
		manager.put(Scope.CURRENT, new DictionaryEntry("Bye", "再见", "en_us", "zh_cn"));

		assertEquals(2, manager.listContexts().size());
		assertTrue(manager.isCurrent("multiplayer/example.test"));
		manager.deleteContext("multiplayer/example.test");
		assertEquals(2, manager.listContexts().size(), "the active dictionary cannot be deleted from the other list");
		manager.deleteContext("singleplayer/world_a");
		assertEquals(1, manager.listContexts().size());
		assertFalse(Files.exists(root.resolve("singleplayer/world_a")),
				"clearing a listed dictionary removes its now-empty context directory");
	}

	@Test
	void leavingWorldClearsCurrentDictionaryWithoutDeletingStoredEntries() {
		DictionaryManager manager = new DictionaryManager(() -> root);
		manager.switchContext(CacheContext.singleplayer("world_a"));
		manager.put(Scope.CURRENT, new DictionaryEntry("Hello", "你好", "en_us", "zh_cn"));

		manager.switchContext(null);

		assertFalse(manager.hasCurrentContext());
		assertTrue(manager.entries(Scope.CURRENT).isEmpty());
		assertEquals(1, manager.listContexts().size(), "the world dictionary remains available in the list");
		assertEquals("singleplayer/world_a", manager.listContexts().getFirst().directory());
	}
}
