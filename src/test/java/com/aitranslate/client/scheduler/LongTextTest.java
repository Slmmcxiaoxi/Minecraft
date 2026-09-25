package com.aitranslate.client.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Long text splitting (tenth feedback round).
 * <p>
 * This is the fix for the report "opening a long book page makes the request time
 * out and switches translation off": a page is one literal of up to ~1000
 * characters, which used to travel as a single element. The invariant that makes
 * the split safe is that joining the parts reproduces the original byte for byte
 * - the scheduler re-joins the translated parts exactly that way, and a part that
 * failed keeps its original text.
 */
class LongTextTest {

	@Test
	void shortTextIsNotSplit() {
		assertFalse(LongText.needsSplit("Hello", 400));
		assertEquals(List.of("Hello"), LongText.split("Hello", 400));
	}

	@Test
	void emptyTextStaysEmpty() {
		assertEquals(List.of(""), LongText.split("", 400));
		assertTrue(LongText.split(null, 400).isEmpty());
	}

	@Test
	void paragraphIsPreferredAsCutPoint() {
		String text = "First paragraph.\nSecond paragraph.\nThird paragraph.";
		List<String> parts = LongText.split(text, 20);
		assertTrue(parts.size() > 1, "long text must be split");
		// Every cut lands on a paragraph break, so no paragraph is torn apart.
		for (int i = 0; i < parts.size() - 1; i++) {
			assertTrue(parts.get(i).endsWith("\n"), "part " + i + " should end at the paragraph break: " + parts.get(i));
		}
		assertEquals(text, String.join("", parts));
	}

	@Test
	void sentenceEndIsUsedWhenThereIsNoParagraphBreak() {
		String text = "The gate is sealed. Nobody leaves at night. Trust no one.";
		List<String> parts = LongText.split(text, 25);
		assertTrue(parts.size() >= 2);
		assertTrue(parts.get(0).endsWith(" "), "sentence end keeps the trailing space: '" + parts.get(0) + "'");
		assertEquals(text, String.join("", parts));
	}

	@Test
	void splitIsAlwaysLossless() {
		// The property the scheduler depends on, over several awkward inputs.
		String[] samples = {
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // no boundary at all
				"One, two, three, four, five, six, seven, eight, nine, ten",
				"短句一。短句二。短句三。短句四。短句五。",
				"Line one\nLine two\nLine three\nLine four\nLine five",
				"Mixed 中文 and English, with punctuation! And a very long tail without any stop at all",
				"A\ud83d\ude00B\ud83d\ude00C\ud83d\ude00D\ud83d\ude00E\ud83d\ude00F\ud83d\ude00G",
		};
		for (String sample : samples) {
			for (int limit : new int[] { 5, 7, 10, 16, 33, 64 }) {
				List<String> parts = LongText.split(sample, limit);
				assertEquals(sample, String.join("", parts),
						"join(split(text)) must equal text (limit " + limit + ")");
				assertTrue(parts.size() <= LongText.MAX_SEGMENTS + 1,
						"segment count stays bounded: " + parts.size());
				for (String part : parts) {
					assertFalse(part.isEmpty(), "no empty segment for limit " + limit);
				}
			}
		}
	}

	@Test
	void surrogatePairsAreNotCutInHalf() {
		String text = "A😀B😀C";
		List<String> parts = LongText.split(text, 2);
		assertEquals(text, String.join("", parts));
		for (String part : parts) {
			// A lone surrogate would print as a replacement character.
			assertFalse(part.length() == 1 && Character.isHighSurrogate(part.charAt(0)), "broken surrogate pair");
		}
	}

	@Test
	void joinKeepsTheOriginalForUntranslatedParts() {
		List<String> parts = List.of("Hello. ", "World.", " Goodbye.");
		String joined = LongText.join(parts, java.util.Arrays.asList("你好。 ", null, " 再见。"));
		assertEquals("你好。 World. 再见。", joined);
	}

	@Test
	void joinWithoutResultsIsIdentity() {
		List<String> parts = List.of("a", "b", "c");
		assertEquals("abc", LongText.join(parts, List.of()));
		assertEquals("abc", LongText.join(parts, null));
	}

	@Test
	void segmentCountIsCappedForPathologicalText() {
		// A 10 000 character wall with a 100 character limit would be 100 requests;
		// the cap keeps it at MAX_SEGMENTS (each segment is then simply larger).
		String text = "x".repeat(10_000);
		List<String> parts = LongText.split(text, 100);
		assertTrue(parts.size() <= LongText.MAX_SEGMENTS, "got " + parts.size() + " segments");
		assertEquals(text, String.join("", parts));
	}
}
