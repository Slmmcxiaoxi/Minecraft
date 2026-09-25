package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class TwentyThirdRoundWiringTest {
	private static String source(String relative) throws Exception {
		return Files.readString(Path.of("src/client/java/com/aitranslate/client").resolve(relative));
	}

	@Test
	void lockedContainerOverlayUsesTheContainerSwitchAndLayoutMeasurement() throws Exception {
		String overlay = source("mixin/OverlayMessageMixin.java");
		assertTrue(overlay.contains("containsTranslationKey(component, \"container.isLocked\")"));
		assertTrue(overlay.contains("return TextType.CONTAINER"));
		assertTrue(overlay.contains("TranslationSupport.translated(component, aiTranslate$type(component))"));
	}

	@Test
	void chatRefreshSnapshotsAndRestoresManualScrollState() throws Exception {
		String refresh = source("display/RefreshCoordinator.java");
		assertTrue(refresh.contains("int scrollPosition = accessor.aiTranslate$getChatScrollbarPos()"));
		assertTrue(refresh.contains("accessor.aiTranslate$refreshTrimmedMessages()"));
		assertTrue(refresh.contains("accessor.aiTranslate$setChatScrollbarPos"));
		assertTrue(refresh.contains("accessor.aiTranslate$setNewMessageSinceScroll(unread)"));
	}

	@Test
	void cachedWorldTextNeverEntersTheRequestBranchAgain() throws Exception {
		String support = source("display/TranslationSupport.java");
		String focus = source("focus/FocusTracker.java");
		int cache = support.indexOf("if (cached.isPresent())");
		int miss = support.indexOf("} else {", cache);
		int request = support.indexOf("scheduler.request(type, text, priority", miss);
		assertTrue(cache >= 0 && miss > cache && request > miss,
				"request scheduling must live only in the cache-miss branch");
		assertTrue(focus.contains("rangeCellChanged && AITranslateModClient.config != null"));
		assertTrue(focus.contains("AITranslateModClient.config.enableRangeDetection"));
	}
}
