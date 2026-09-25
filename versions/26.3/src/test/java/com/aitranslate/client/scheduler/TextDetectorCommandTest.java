package com.aitranslate.client.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The command filter.
 * <p>
 * Eighth feedback round: the scoreboard sidebar title stopped being translated. The
 * cause was this filter - it rejected any text that merely <em>started</em> with a
 * command word, and the map's objective was called "Kill the Dragon".
 * <p>
 * Only real command syntax is skipped now (leading slash, target selector, relative
 * coordinates). A command that arrives as the argument of a vanilla message is still
 * skipped, but that is decided where the arguments are read
 * ({@code ComponentExtractor#collectArgs}), not for every piece of text.
 */
class TextDetectorCommandTest {

	private final TextDetector detector = new TextDetector();

	@Test
	void proseStartingWithACommandWordIsTranslated() {
		assertTrue(detector.shouldTranslate("Kill the Dragon"), "scoreboard title regression");
		assertTrue(detector.shouldTranslate("Help the villagers"));
		assertTrue(detector.shouldTranslate("Give me a sign"));
		assertTrue(detector.shouldTranslate("Time is running out"));
		assertTrue(detector.shouldTranslate("Weather the storm"));
		assertTrue(detector.shouldTranslate("Team up with the guard"));
		assertTrue(detector.shouldTranslate("List of the fallen"));
		assertTrue(detector.shouldTranslate("Return to the village"));
	}

	@Test
	void commandSyntaxIsSkipped() {
		assertFalse(detector.shouldTranslate("/tell @a Hello"));
		assertFalse(detector.shouldTranslate("tell @a Hello"));
		assertFalse(detector.shouldTranslate("setblock ~ ~ ~ stone"));
		assertFalse(detector.shouldTranslate("tp @s ^1 ^ ^2"));
		assertFalse(detector.shouldTranslate("give @p minecraft:diamond 1"));
	}

	@Test
	void ordinaryTextIsStillTranslated() {
		assertTrue(detector.shouldTranslate("Beware the dark"));
		assertTrue(detector.shouldTranslate("Meet me at the old watchtower"));
		assertTrue(detector.shouldTranslate("Blade of Dawn"));
	}

	@Test
	void argumentCommandsAreDetectedSeparately() {
		// Consulted only for the arguments of command feedback messages, where a
		// command really is passed as a component argument.
		assertTrue(com.aitranslate.client.capture.ComponentExtractor.isCommandLike("tell xxx Hello"));
		assertTrue(com.aitranslate.client.capture.ComponentExtractor.isCommandLike("give @p stone"));

		// The protection itself lives in the extractor and is key based: the command
		// block message keeps its argument, ordinary messages do not lose theirs.
		net.minecraft.network.chat.Component commandBlock = net.minecraft.network.chat.Component
				.translatable("advMode.setCommand.success",
						net.minecraft.network.chat.Component.literal("tell xxx Hello"));
		assertTrue(com.aitranslate.client.capture.ComponentExtractor.uniqueTexts(commandBlock).isEmpty(),
				"the command argument of a command feedback message must not be translated");

		net.minecraft.network.chat.Component mapTitle = net.minecraft.network.chat.Component
				.translatable("chat.type.text", "Steve", "Kill the Dragon");
		assertTrue(com.aitranslate.client.capture.ComponentExtractor.uniqueTexts(mapTitle)
				.contains("Kill the Dragon"),
				"a title that starts with a command word is still translated");
	}
}
