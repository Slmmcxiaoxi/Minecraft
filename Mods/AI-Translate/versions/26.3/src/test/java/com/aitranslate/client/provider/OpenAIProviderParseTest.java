package com.aitranslate.client.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Protocol handling of {@link OpenAIProvider}.
 * <p>
 * These rules exist because the answer of a model is a piece of untrusted input:
 * an array with a different number of elements must be rejected (the order
 * carries the meaning, so a short answer cannot be mapped by position), while an
 * array wrapped in prose must still be accepted.
 */
class OpenAIProviderParseTest {

	@Test
	void plainArrayIsAccepted() {
		List<String> out = OpenAIProvider.parseTranslations("[\"当心黑暗\",\"立刻折返\"]", 2);
		assertEquals(List.of("当心黑暗", "立刻折返"), out);
	}

	@Test
	void fencedArrayIsAccepted() {
		List<String> out = OpenAIProvider.parseTranslations("```json\n[\"一\",\"二\"]\n```", 2);
		assertEquals(List.of("一", "二"), out);
	}

	@Test
	void arrayWrappedInProseIsSalvaged() {
		List<String> out = OpenAIProvider.parseTranslations("Sure! Here you go: [\"一\",\"二\"]", 2);
		assertEquals(List.of("一", "二"), out);
	}

	@Test
	void escapingIsPreserved() {
		List<String> out = OpenAIProvider.parseTranslations("[\"a\\\"b\",\"line\\nbreak\"]", 2);
		assertEquals("a\"b", out.get(0));
		assertEquals("line\nbreak", out.get(1));
	}

	@Test
	void shortArrayIsRejected() {
		// The failure observed with a local 7B translation model: 12 of 19 elements.
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenAIProvider.parseTranslations("[\"一\",\"二\"]", 19));
		assertEquals("Expected 19 translations but got 2", error.getMessage());
	}

	@Test
	void tooLongArrayIsRejected() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenAIProvider.parseTranslations("[\"一\",\"二\",\"三\"]", 2));
		assertEquals("Expected 2 translations but got 3", error.getMessage());
	}

	@Test
	void proseWithoutArrayIsRejected() {
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> OpenAIProvider.parseTranslations("I cannot translate that.", 1));
		assertTrue(error.getMessage().startsWith("Expected "), error.getMessage());
	}

	@Test
	void emptyAnswerIsRejected() {
		assertThrows(IllegalStateException.class, () -> OpenAIProvider.parseTranslations("[]", 1));
	}

	@Test
	void wrongCountAddsACorrectiveInstructionToTheRetry() {
		String hint = OpenAIProvider.retryHint(
				new IllegalStateException("Expected 19 translations but got 12"), 19, null);
		assertNotNull(hint);
		assertTrue(hint.contains("exactly 19 strings"), hint);
	}

	@Test
	void otherErrorsKeepThePreviousHint() {
		String previous = "keep me";
		assertSame(previous, OpenAIProvider.retryHint(new IllegalStateException("request timed out"), 5, previous));
		assertEquals(null, OpenAIProvider.retryHint(new IllegalStateException("request timed out"), 5, null));
	}
}
