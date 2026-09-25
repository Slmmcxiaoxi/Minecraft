package com.aitranslate.client.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The recovery ladder of the eleventh feedback round.
 * <p>
 * A local 1.8B model does not reliably answer with a one element JSON array, and
 * the old behaviour (retry the same broken request, then drop the text for a
 * minute) meant that a book page could stay in English forever. For a single text
 * the protocol now alternates between the JSON array and a plain answer.
 */
class OpenAIProviderFallbackTest {

	@Test
	void aSingleTextSwitchesToThePlainProtocolAfterABadAnswer() {
		assertTrue(OpenAIProvider.nextPlainMode(new ModelAnswerException("Expected 1 translations but got 0"),
				false, 1));
	}

	@Test
	void thePlainAttemptSwitchesBackToJson() {
		assertFalse(OpenAIProvider.nextPlainMode(new ModelAnswerException("Expected 1 translations but got 0"),
				true, 1));
	}

	@Test
	void aTimeoutKeepsTheProtocol() {
		Throwable timeout = new TranslationTransportException("请求超时（服务器仍在处理）", false, true, null);
		assertFalse(OpenAIProvider.nextPlainMode(timeout, false, 1));
		assertTrue(OpenAIProvider.nextPlainMode(timeout, true, 1));
	}

	@Test
	void aBatchNeverSwitchesProtocol() {
		// With several texts the order carries the meaning, so a plain answer could
		// not be mapped onto the elements at all: whatever the answer looked like,
		// the next attempt uses the protocol the batch started with.
		assertFalse(OpenAIProvider.nextPlainMode(new ModelAnswerException("Expected 20 translations but got 18"),
				false, 20));
		assertTrue(OpenAIProvider.nextPlainMode(new ModelAnswerException("Expected 2 translations but got 1"),
				true, 2));
	}

	@Test
	void thePlainRetryHintDoesNotAskForJson() {
		String hint = OpenAIProvider.retryHint(new ModelAnswerException("Expected 1 translations but got 0"), 1, null,
				true);
		assertTrue(hint.contains("no JSON"), hint);
		assertFalse(hint.contains("array of exactly"), hint);
	}

	@Test
	void aTruncatedAnswerIsToldToBeShorter() {
		String hint = OpenAIProvider.retryHint(new ModelAnswerException("Answer truncated (token limit reached)"), 1,
				null, false);
		assertTrue(hint.contains("cut off"), hint);
	}

	@Test
	void truncationIsReportedAsAModelAnswerProblem() {
		// Not a network problem: the API answered, the answer was just unusable.
		assertTrue(OpenAIProvider.classify(new ModelAnswerException("Answer truncated (token limit reached)"))
				instanceof ModelAnswerException);
	}

	@Test
	void theTokenBudgetGrowsWithThePayloadAndIsBounded() {
		int small = OpenAIProvider.maxTokensFor(List.of("hi"));
		int page = OpenAIProvider.maxTokensFor(List.of("x".repeat(184)));
		int huge = OpenAIProvider.maxTokensFor(List.of("x".repeat(8000)));
		assertEquals(256, small);
		assertTrue(page > 256 && page < 1024, "page budget was " + page);
		assertEquals(4096, huge);
	}
}
