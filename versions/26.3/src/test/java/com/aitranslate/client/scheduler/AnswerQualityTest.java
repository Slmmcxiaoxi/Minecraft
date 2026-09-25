package com.aitranslate.client.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Answer sanity: the model repeating its input (fifteenth feedback round).
 * <p>
 * The report was "the team prefix and suffix are not translated again". The prefix was
 * the vanilla-value filter's fault, the suffix was this: the model answered the short
 * fragment {@code the Brave} with {@code the Brave}, the answer was cached, and from
 * then on the text was a cache hit - it was never asked again and stayed English while
 * the text around it was translated.
 * <p>
 * The rule has to be narrow, because an unchanged answer is often the <em>right</em>
 * one (ids, codes, "OK", player names).
 */
class AnswerQualityTest {
	@Test
	void chineseTargetRejectsKoreanAndJapaneseScripts() {
		assertTrue(AnswerQuality.hasClearlyWrongScript("빨간색 황색", "zh_cn"));
		assertTrue(AnswerQuality.hasClearlyWrongScript("赤いイエロー", "zh-cn"));
		assertFalse(AnswerQuality.hasClearlyWrongScript("红色 黄色", "zh_cn"));
		assertFalse(AnswerQuality.hasClearlyWrongScript("红色 Steve", "zh_cn"));
	}

	@Test
	void aShortPhraseAnsweredUnchangedIsAnEcho() {
		assertTrue(AnswerQuality.isEcho("the Brave", "the Brave", false));
		assertTrue(AnswerQuality.isEcho("the Bold", " the Bold ", false), "whitespace around the answer is ignored");
		assertTrue(AnswerQuality.isEcho("Kill the Dragon", "Kill the Dragon", false));
		assertTrue(AnswerQuality.isEcho("\u00a7aDeath and taxes", "\u00a7aDeath and taxes", false),
				"formatting codes are ignored in the comparison");
	}

	@Test
	void aRealTranslationIsNotAnEcho() {
		assertFalse(AnswerQuality.isEcho("the Brave", "勇敢者", false));
		assertFalse(AnswerQuality.isEcho("Kill the Dragon", "杀死龙", false));
	}

	@Test
	void identifiersAndShortCodesMayStayUnchanged() {
		// These are the cases where an unchanged answer is correct: the mod must not
		// treat them as failures (that would mean a retry every minute for nothing).
		assertFalse(AnswerQuality.isEcho("AT", "AT", false));
		assertFalse(AnswerQuality.isEcho("HP", "HP", false));
		assertFalse(AnswerQuality.isEcho("aitranslate:demo", "aitranslate:demo", false));
		assertFalse(AnswerQuality.isEcho("1234", "1234", false));
		assertFalse(AnswerQuality.isEcho("Death", "Death", false), "a single short word is left alone");
	}

	@Test
	void textAlreadyInTheTargetLanguageIsNeverAnEcho() {
		assertFalse(AnswerQuality.isEcho("夜幕降临 and so does hope", "夜幕降临 and so does hope", true));
	}

	@Test
	void longSingleWordsThatAreNotIdsAreEchoes() {
		// A long lowercase word is prose, not an id; an unchanged answer there is a real
		// failure ("Guidance"), while "aitrans_demo" is retried once at most.
		assertTrue(AnswerQuality.isEcho("Guidance", "Guidance", false));
	}

	@Test
	void theStoredCacheRepairUsesTheSameRule() {
		assertTrue(AnswerQuality.isStoredEcho("the Brave", "the Brave"));
		assertFalse(AnswerQuality.isStoredEcho("the Brave", "勇敢者"));
		assertFalse(AnswerQuality.isStoredEcho("AT", "AT"));
	}

	@Test
	void nullAndEmptyAnswersAreNotEchoes() {
		assertFalse(AnswerQuality.isEcho(null, "x", false));
		assertFalse(AnswerQuality.isEcho("x", null, false));
		assertFalse(AnswerQuality.isEcho("", "", false));
	}
}
