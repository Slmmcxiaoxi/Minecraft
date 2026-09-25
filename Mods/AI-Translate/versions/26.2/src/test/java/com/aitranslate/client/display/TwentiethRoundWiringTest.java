package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TwentiethRoundWiringTest {
	private static String source(String relative) throws Exception {
		return Files.readString(Path.of("src/client/java/com/aitranslate/client/").resolve(relative));
	}

	@Test
	void containerTitleKeepsLeftAlignmentAndRemeasuresVanillaCentering() throws Exception {
		String source = source("mixin/ContainerTitleMixin.java");
		assertTrue(source.contains("translatedTitleX"));
		assertTrue(source.contains("return originalX"));
		assertFalse(source.contains("centeredX"));
		assertTrue(source.contains("ordinal = 0"), "only the container title, not the inventory label, is replaced");
	}

	@Test
	void bookEditorSupportsButtonOnlyForcedPerScreenTranslation() throws Exception {
		String state = source("display/ScreenOriginalMode.java");
		String editor = source("mixin/MultiLineEditBoxMixin.java");
		String key = source("mixin/ScreenKeyMixin.java");
		assertTrue(state.contains("forcesTranslation(TextType type)"));
		assertTrue(editor.contains("ScreenOriginalMode.forcesTranslation(TextType.BOOK)"));
		assertFalse(key.contains("toggleBookEditorBeforeInput"));
		assertTrue(key.contains("instanceof net.minecraft.client.gui.screens.inventory.BookEditScreen"));
	}

	@Test
	void schedulerUsesIndexedPriorityQueuesWithoutPerBatchSort() throws Exception {
		String source = source("scheduler/TranslationScheduler.java");
		assertTrue(source.contains("priorityQueues"));
		assertTrue(source.contains("MAX_CONCURRENT_REQUESTS = 2"));
		assertTrue(source.contains("LOW_PRIORITY_TTL_MS"));
		assertFalse(source.contains("candidates.sort("));
	}

	@Test
	void releaseContainsNoWorldSelfTestHarness() throws Exception {
		assertFalse(Files.exists(Path.of("src/client/java/com/aitranslate/client/command/SelfTest.java")));
		assertFalse(source("command/AITranslateCommand.java").contains("selftest"));
		assertFalse(source("AITranslateModClient.java").contains("SelfTest"));
	}
}
