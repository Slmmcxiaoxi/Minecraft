package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SignTranslationLayoutTest {
	@Test
	void wrappedRowsUseInnerWordsForOneWholeRequestAndRestoreEverySymbol() {
		List<String> original = List.of("(death)", "<sheep>", "[sea]", "{water}");
		assertEquals("death\nsheep\nsea\nwater", SignTranslationLayout.requestText(original));
		assertEquals(List.of("(死亡)", "<绵羊>", "[海洋]", "{水}"),
				SignTranslationLayout.layout(original, "死亡\n绵羊\n海洋\n水"));
	}

	@Test
	void modelReturnedWrappersAreNotDuplicated() {
		List<String> original = List.of("(death)", "<sheep>", "[sea]", "{water}");
		assertEquals(original, SignTranslationLayout.layout(original, "(death)\n<sheep>\n[sea]\n{water}"));
	}
	@Test
	void theWholeFourLineShapeIsTheCacheKey() {
		assertEquals("Escape the\nlost city\n\n",
				SignTranslationLayout.cacheKey(List.of("Escape the", "lost city")));
		assertTrue(!SignTranslationLayout.cacheKey(List.of("Escape the", "lost city"))
				.equals(SignTranslationLayout.cacheKey(List.of("Escape the lost city"))));
	}

	@Test
	void explicitTranslationLinesKeepTheOriginalOccupiedRows() {
		List<String> laidOut = SignTranslationLayout.layout(
				List.of("", "Escape the", "lost city", ""), "逃离\n失落之城");
		assertEquals(List.of("", "逃离", "失落之城", ""), laidOut);
	}

	@Test
	void aSingleAnswerIsWrappedIntoAtMostFourShortLines() {
		List<String> laidOut = SignTranslationLayout.layout(
				List.of("The ancient gate", "opens at midnight", "", ""),
				"古老城门将在午夜钟声响起之时缓缓开启请尽快离开这里否则危险将会降临");
		assertEquals(4, laidOut.size());
		for (String line : laidOut) {
			assertTrue(line.codePointCount(0, line.length()) <= SignTranslationLayout.MAX_CHARS_PER_LINE,
					() -> "line exceeds sign limit: " + line);
		}
	}

	@Test
	void unchangedAnswerKeepsEveryOriginalRow() {
		List<String> original = List.of("Escape the", "lost city", "", "");
		assertEquals(original, SignTranslationLayout.layout(original,
				SignTranslationLayout.cacheKey(original)));
	}
}
