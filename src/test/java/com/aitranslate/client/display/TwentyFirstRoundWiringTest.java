package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TwentyFirstRoundWiringTest {
	private static String source(String relative) throws Exception {
		return Files.readString(Path.of("src/client/java/com/aitranslate/client/").resolve(relative));
	}

	@Test
	void chatRequestsCarryAnExplicitLineRefreshCallback() throws Exception {
		String support = source("display/TranslationSupport.java");
		String refresh = source("display/RefreshCoordinator.java");
		assertTrue(support.contains("RefreshCoordinator::requestChatTranslationRefresh"));
		assertTrue(refresh.contains("CHAT_TRANSLATION_READY.getAndSet(false)"));
	}

	@Test
	void visibilityGateIsCompletelyRemoved() throws Exception {
		assertFalse(source("config/ModConfig.java").contains("translateVisibleOnly"));
		assertFalse(source("scheduler/TranslationScheduler.java").contains("skippedNotVisible"));
		assertFalse(source("display/TranslationSupport.java").contains("isBackgroundOnly"));
	}

	@Test
	void containerHookRemeasuresOnlyVanillaCenteredTitles() throws Exception {
		String mixin = source("mixin/ContainerTitleMixin.java");
		assertTrue(mixin.contains("originalX == originalCenteredX"));
		assertTrue(mixin.contains("(imageWidth - font.width(translated)) / 2"));
		assertFalse(mixin.contains("centeredX"));
	}

	@Test
	void multiKeyImplementationIsGone() {
		assertFalse(Files.exists(Path.of("src/client/java/com/aitranslate/client/keybinding/KeyCombo.java")));
		assertFalse(Files.exists(Path.of("src/client/java/com/aitranslate/client/keybinding/KeyComboManager.java")));
		assertFalse(Files.exists(Path.of("src/client/java/com/aitranslate/client/config/entry/KeyComboEntry.java")));
	}

	@Test
	void editorKeysNeverPreemptTextInput() throws Exception {
		String key = source("mixin/ScreenKeyMixin.java");
		assertFalse(key.contains("aiTranslate$toggleBookEditorBeforeInput"));
		assertTrue(key.contains("instanceof net.minecraft.client.gui.screens.inventory.BookEditScreen"));
		assertTrue(key.contains("ScreenTextInputs.isTextInputFocused(screen)"));
	}
}
