package com.aitranslate.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageMigrationTest {
	@TempDir Path temp;

	@Test
	void movesOnlyDictionariesAndKeepsCacheFiles() throws Exception {
		Path cache = temp.resolve("cache");
		Path dictionary = temp.resolve("dictionary");
		Path oldCurrent = cache.resolve("singleplayer/world/dictionary.json");
		Path oldGlobal = cache.resolve("global/dictionary.json");
		Path cacheFile = cache.resolve("singleplayer/world/chat.json");
		Files.createDirectories(oldCurrent.getParent());
		Files.createDirectories(oldGlobal.getParent());
		Files.writeString(oldCurrent, "{\"entries\":[]}");
		Files.writeString(oldGlobal, "{\"entries\":[]}");
		Files.writeString(cacheFile, "{\"entries\":{}}");

		StorageMigration.migrateLegacyDictionaries(cache, dictionary);

		assertTrue(Files.isRegularFile(dictionary.resolve("singleplayer/world/dictionary.json")));
		assertTrue(Files.isRegularFile(dictionary.resolve("global/dictionary.json")));
		assertFalse(Files.exists(oldCurrent));
		assertFalse(Files.exists(oldGlobal));
		assertEquals("{\"entries\":{}}", Files.readString(cacheFile));
	}
}
