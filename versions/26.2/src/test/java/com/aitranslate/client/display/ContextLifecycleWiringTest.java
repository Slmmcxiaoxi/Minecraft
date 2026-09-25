package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ContextLifecycleWiringTest {
	private static String source(String relative) throws IOException {
		return Files.readString(Path.of("src/client/java/com/aitranslate/client").resolve(relative));
	}

	@Test
	void disconnectAndConfigOpenBothSynchronizeTheCurrentContext() throws IOException {
		String client = source("AITranslateModClient.java");
		String config = source("config/ConfigScreen.java");

		assertTrue(client.contains("ClientPlayConnectionEvents.DISCONNECT.register"));
		assertTrue(client.contains("cacheManager.switchContext(null)"));
		assertTrue(client.contains("dictionaryManager.switchContext(null)"));
		assertTrue(config.contains("AITranslateModClient.synchronizeContext(Minecraft.getInstance())"));
	}

	@Test
	void cacheAndPendingTranslationsCannotLeakAcrossWorlds() throws IOException {
		String cache = source("cache/CacheManager.java");
		String scheduler = source("scheduler/TranslationScheduler.java");

		assertTrue(cache.contains("if (next == null)"));
		assertTrue(cache.contains("context = null"));
		assertTrue(cache.contains("disk = null"));
		assertTrue(cache.contains("memory.clear()"));
		assertTrue(scheduler.contains("contextGeneration.incrementAndGet()"));
		assertTrue(scheduler.contains("batchContext != contextGeneration.get()"));
	}
}
