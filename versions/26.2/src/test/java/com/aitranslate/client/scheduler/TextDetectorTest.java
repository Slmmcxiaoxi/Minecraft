package com.aitranslate.client.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class TextDetectorTest {
	private final TextDetector detector = new TextDetector();

	@Test
	void translatesEnglishProse() {
		assertTrue(detector.shouldTranslate("The night falls, and so does hope."));
		assertTrue(detector.shouldTranslate("Chapter I - The Beginning"));
	}

	@Test
	void skipsAlreadyTranslatedText() {
		assertFalse(detector.shouldTranslate("夜幕降临，希望也随之陨落。"));
		assertFalse(detector.shouldTranslate("你好，冒险者"));
	}

	@Test
	void skipsNumbersAndSymbols() {
		assertFalse(detector.shouldTranslate("1234"));
		assertFalse(detector.shouldTranslate("-42, 128, 7"));
		assertFalse(detector.shouldTranslate("!!! ??? ..."));
		assertFalse(detector.shouldTranslate("§a§l"));
	}

	@Test
	void skipsPlaceholderOnlyText() {
		assertFalse(detector.shouldTranslate("%s"));
		assertFalse(detector.shouldTranslate("{0} / %1$s"));
	}

	@Test
	void skipsUrlsCommandsAndIds() {
		assertFalse(detector.shouldTranslate("https://example.com/story"));
		assertFalse(detector.shouldTranslate("/give @p minecraft:diamond"));
		assertFalse(detector.shouldTranslate("minecraft:diamond_sword"));
		assertFalse(detector.shouldTranslate("block.minecraft.stone"));
	}

	@Test
	void skipsKnownPlayerNames() {
		detector.setPlayerNames(java.util.Set.of("Steve", "Alex"));
		assertFalse(detector.shouldTranslate("Steve"));
		assertTrue(detector.shouldTranslate("Steve walks into the tavern"));
	}

	@Test
	void findsAPlayerNameInsideAFlattenedDecoratedName() {
		detector.setPlayerNames(java.util.Set.of("Slmmcxiaoxi", "MoonStars_"));
		assertEquals("slmmcxiaoxi", detector.embeddedPlayerName("[Winner]Slmmcxiaoxi<Loser>"));
		assertEquals("moonstars_", detector.embeddedPlayerName("[Winner]_MoonStars_<Loser>"));
		assertNull(detector.embeddedPlayerName("[Winner]Villager<Loser>"));
	}

	@Test
	void skipsLogsAndTimestamps() {
		assertFalse(detector.shouldTranslate("[12:34:56] [INFO] Translation request completed"));
		assertFalse(detector.shouldTranslate("2026-09-22 12:34:56 ERROR connection failed"));
	}

	@Test
	void aProfileCanBeProtectedImmediatelyBeforeTheNextTick() {
		detector.protectPlayerName("Notch");
		assertFalse(detector.shouldTranslate("Notch"));
		assertFalse(detector.shouldTranslate("[Notch]"));
	}

	@Test
	void skipsTooShortText() {
		assertFalse(detector.shouldTranslate("a"));
		assertFalse(detector.shouldTranslate("  "));
		assertFalse(detector.shouldTranslate(null));
	}

	@Test
	void unusualUnicodeAndControlCharactersNeverCrashDetection() {
		for (String value : java.util.List.of("😀🚀✨", "hello\u0000world", "A\u200DB", "\uD800broken", "café déjà vu")) {
			assertDoesNotThrow(() -> detector.skipReason(value), value);
		}
	}

	@Test
	void detectsTargetLanguageRatio() {
		assertTrue(detector.isMostlyTargetLanguage("这是中文文本"));
		assertFalse(detector.isMostlyTargetLanguage("This is English"));
		assertFalse(detector.isMostlyTargetLanguage("12345"));
	}

	@Test
	void autoSourceAcceptsCommonLatinAndNonLatinLanguagesForChineseTarget() {
		detector.setLanguages("auto", "zh_cn");
		assertTrue(detector.shouldTranslate("Guten Morgen, Reisender"));
		assertTrue(detector.shouldTranslate("Bonjour, voyageur"));
		assertTrue(detector.shouldTranslate("こんにちは、旅人さん"));
		assertTrue(detector.shouldTranslate("안녕하세요 여행자님"));
		assertTrue(detector.shouldTranslate("Привет, путешественник"));
		assertFalse(detector.shouldTranslate("你好，旅行者"));
	}

	@Test
	void explicitEnglishSourceRejectsOtherDetectedLanguages() {
		detector.setLanguages("en_us", "zh_cn");
		assertTrue(detector.shouldTranslate("The night falls and the player enters the world"));
		assertFalse(detector.shouldTranslate("Guten Morgen, dies ist ein deutscher Text"));
		assertFalse(detector.shouldTranslate("Wasser"), "a distinctive one-word German label is filtered too");
		assertFalse(detector.shouldTranslate("Bonjour, ceci est un texte pour le voyageur"));
		assertFalse(detector.shouldTranslate("こんにちは、旅人さん"));
		assertFalse(detector.shouldTranslate("你好，旅行者"));
	}

	@Test
	void explicitJapaneseSourceRejectsEnglishAndGerman() {
		detector.setLanguages("ja_jp", "zh_cn");
		assertTrue(detector.shouldTranslate("こんにちは、旅人さん"));
		assertFalse(detector.shouldTranslate("The player enters the world"));
		assertFalse(detector.shouldTranslate("Guten Morgen, dies ist ein deutscher Text"));
	}

	/**
	 * Fifteenth feedback round: a scoreboard team prefix/suffix is map text and has to
	 * be translated.
	 * <p>
	 * The report was "the team prefix and suffix are not translated again". The cause
	 * was the vanilla-value filter: a prefix like {@code [Guardian] } is reduced to
	 * {@code Guardian} by the extractor, and {@code Guardian} is a value of the vanilla
	 * language file (the mob), so the whole prefix was skipped while the suffix beside
	 * it was translated. The filter is gone - the game only localises
	 * {@code translatable} components, so a literal that matches a vanilla word is
	 * still plain English on screen and must be translated.
	 */
	@Test
	void translatesTeamPrefixesAndSuffixesThatMatchVanillaWords() {
		assertTrue(detector.shouldTranslate("Guardian"), "a vanilla mob name as map text is still map text");
		assertTrue(detector.shouldTranslate("[Guardian]"), "the reported team prefix");
		assertTrue(detector.shouldTranslate("Guardian "));
		assertTrue(detector.shouldTranslate("the Brave"), "the suffix next to it");
		assertTrue(detector.shouldTranslate("Stone"));
		assertTrue(detector.shouldTranslate("Diamond Sword"), "a literal copy of a vanilla item name is map text too");
	}

	/** The other half of the rule: data is still data. */
	@Test
	void stillSkipsPlayerNamesEvenWhenWrappedAsATeamPrefixWouldLook() {
		detector.setPlayerNames(java.util.Set.of("Steve"));
		assertFalse(detector.shouldTranslate("Steve"), "a real player name is never translated");
		assertFalse(detector.shouldTranslate("[Steve]"), "a wrapper around a player name does not change that");
	}
}
