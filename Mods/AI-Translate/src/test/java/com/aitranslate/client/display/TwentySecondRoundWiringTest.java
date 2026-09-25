package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TwentySecondRoundWiringTest {
	private static String source(String relative) throws Exception {
		return Files.readString(Path.of("src/client/java/com/aitranslate/client").resolve(relative));
	}

	@Test
	void oldPollutedCacheEntriesAreCleanedAndRewritten() throws Exception {
		String scheduler = source("scheduler/TranslationScheduler.java");
		assertTrue(scheduler.contains("ModelAnswers.cleanTranslation(cached.get())"));
		assertTrue(scheduler.contains("cacheManager.current().put(text, cleaned"));
	}

	@Test
	void playerTagsProtectOnlyTheIdentity() throws Exception {
		String entity = source("mixin/EntityNameMixin.java");
		assertTrue(entity.contains("ComponentExtractor.playerIdentity(component)"));
		assertTrue(entity.contains("protectPlayerName(identity)"));
		assertTrue(entity.contains("TranslationSupport.translated(component, TextType.ENTITY_NAME, priority)"));
	}

	@Test
	void worldSourcesUseTheConfigurableRangeGate() throws Exception {
		String sign = source("mixin/SignTextMixin.java");
		String entity = source("mixin/EntityNameMixin.java");
		String display = source("mixin/TextDisplayMixin.java");
		assertTrue(sign.contains("withinTranslationRange(TextType.SIGN"));
		assertTrue(entity.contains("withinTranslationRange("));
		assertTrue(display.contains("withinTranslationRange("));
	}
}
