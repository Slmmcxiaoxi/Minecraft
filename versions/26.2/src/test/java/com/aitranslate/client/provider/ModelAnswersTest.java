package com.aitranslate.client.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The answer shapes the eleventh feedback round was about.
 * <p>
 * Every string in here is a real answer of the local Hy-MT2-1.8B model
 * (llama.cpp, the setup the "book page 19/20 never translates" report came from),
 * captured with the exact request body the mod sends. The first one is the root
 * cause: the model pastes the request envelope after its own answer, which makes
 * the whole reply invalid JSON - and the old parser reported it as
 * "Expected 1 translations but got 0", so the page was dropped and stayed in
 * English even after a re-open.
 */
class ModelAnswersTest {

	/** Captured from the model answering page 9 of the test book. */
	private static final String ECHOED_ENVELOPE = "[[\"对其他来说，SAM令人恐惧：一位披着翠绿火焰的银甲猎人，"
			+ "速度快到能在敌人反应过来之前就将其撕裂。但对Firefly而言，这盔甲既是牢笼也是保护。]], "
			+ "\"source_language\": \"auto\", \"target_language\": \"zh_cn\"]";

	@Test
	void echoedInputEnvelopeIsSalvaged() {
		List<String> out = ModelAnswers.parseArray(ECHOED_ENVELOPE, 1);
		assertEquals(1, out.size());
		assertTrue(out.get(0).startsWith("对其他来说"), out.get(0));
	}

	@Test
	void inputFieldAfterTheAnswerIsNotATranslation() {
		List<String> out = ModelAnswers.parseArray("[\"译文\", \"source_language\": \"auto\"]", 1);
		assertEquals(List.of("译文"), out);
	}

	@Test
	void rawNewlineInsideAStringIsKept() {
		// What a model emits for a multi-line book page: a literal newline instead
		// of the \n escape. Strict JSON cannot read it.
		String answer = "[\"第一行\n\n第二行\"]";
		assertEquals(List.of("第一行\n\n第二行"), ModelAnswers.parseArray(answer, 1));
	}

	@Test
	void truncatedArrayIsSalvaged() {
		assertEquals(List.of("一", "二"), ModelAnswers.parseArray("[\"一\",\"二\"", 2));
	}

	@Test
	void truncatedMidStringKeepsWhatWasRead() {
		assertEquals(List.of("完整的一句", "被截断"), ModelAnswers.parseArray("[\"完整的一句\",\"被截断", 2));
	}

	@Test
	void extraNestingLevelIsFlattened() {
		assertEquals(List.of("一", "二"), ModelAnswers.parseArray("[[\"一\"],[\"二\"]]", 2));
	}

	@Test
	void objectElementsAreRead() {
		assertEquals(List.of("一", "二"),
				ModelAnswers.parseArray("[{\"text\":\"一\"},{\"text\":\"二\"}]", 2));
	}

	@Test
	void wrappedObjectIsUnwrapped() {
		assertEquals(List.of("一", "二"), ModelAnswers.parseArray("{\"translations\":[\"一\",\"二\"]}", 2));
	}

	@Test
	void unterminatedFenceIsAccepted() {
		assertEquals(List.of("一", "二"), ModelAnswers.parseArray("```json\n[\"一\",\"二\"]", 2));
	}

	@Test
	void proseAroundTheArrayIsStillAccepted() {
		assertEquals(List.of("一", "二"), ModelAnswers.parseArray("Sure! [\"一\",\"二\"] done.", 2));
	}

	@Test
	void aShortAnswerIsStillRejected() {
		// The order carries the meaning: two elements must never be mapped onto the
		// first two of twenty.
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> ModelAnswers.parseArray("[\"一\",\"二\"]", 20));
		assertEquals("Expected 20 translations but got 2", error.getMessage());
	}

	@Test
	void elementsThatAreNotStringsAreNotInvented() {
		// An envelope echoed without colons cannot be told apart from a translation,
		// so it is rejected here - the plain-text fallback of the provider is the
		// recovery for a single text.
		assertThrows(IllegalStateException.class,
				() -> ModelAnswers.parseArray("[\"译文\", \"source_language\", \"auto\"]", 1));
	}

	@Test
	void emptyAnswerIsRejected() {
		assertThrows(IllegalStateException.class, () -> ModelAnswers.parseArray("[]", 1));
	}

	@Test
	void onePhraseSplitIntoSeveralTranslationsIsRejoined() {
		assertEquals(List.of("红色 黄色"), ModelAnswers.parseArray("[\"红色\",\"黄色\"]", 1));
	}

	// ------------------------------------------------------- plain answers

	@Test
	void plainAnswerIsUsedAsItIs() {
		assertEquals("译文", ModelAnswers.parsePlain("译文", "Hello"));
	}

	@Test
	void plainAnswerQuotesAndLabelsAreStripped() {
		assertEquals("译文", ModelAnswers.parsePlain("\"译文\"", "Hello"));
		assertEquals("译文", ModelAnswers.parsePlain("Translation: 译文", "Hello"));
		assertEquals("译文", ModelAnswers.parsePlain("```\n译文\n```", "Hello"));
		assertEquals("译文", ModelAnswers.parsePlain("[\"译文\"]", "Hello"));
	}

	@Test
	void plainAnswerKeepsItsLineBreaks() {
		assertEquals("第一行\n\n第二行", ModelAnswers.parsePlain("第一行\n\n第二行", "Line one\n\nLine two"));
	}

	@Test
	void plainAnswerThatRepeatsTheInputIsRejected() {
		assertNull(ModelAnswers.parsePlain("Hello, adventurer!", "Hello, adventurer!"));
	}

	@Test
	void plainAnswerThatRepeatsTheRequestEnvelopeIsRejected() {
		assertNull(ModelAnswers.parsePlain("{\"texts\":[\"Hello\"],\"source_language\":\"auto\"}", "Hello"));
	}

	@Test
	void emptyPlainAnswerIsRejected() {
		assertNull(ModelAnswers.parsePlain("   ", "Hello"));
	}

	@Test
	void componentJsonInsideAnAnswerIsReducedToVisibleText() {
		assertEquals("注意", ModelAnswers.parsePlain("{\"text\":\"注意\"}", "Notice"));
		assertEquals(List.of("注意"), ModelAnswers.parseArray("[\"{\\\"text\\\":\\\"注意\\\"}\"]", 1));
		assertEquals(List.of("注意"), ModelAnswers.parseArray("[{\"text\":\"注意\",\"color\":\"red\"}]", 1));
	}

	@Test
	void fencedComponentJsonIsReducedToVisibleText() {
		assertEquals("注意", ModelAnswers.parsePlain("```json\n{\"text\":\"注意\"}\n```", "Notice"));
	}

	@Test
	void modelDecorationLabelsAndEndMarkersAreRemoved() {
		assertEquals("译文", ModelAnswers.parsePlain("Translation result: （译文）\n---\nEND", "Text"));
		assertEquals(List.of("译文"), ModelAnswers.parseArray("[\"【译文】\\nEND\"]", 1));
	}

	@Test
	void modelAddedSymbolWrappersAreRemovedWithoutDeletingTheirText() {
		for (String answer : List.of("{水}", "<绵羊>", "[海洋]", "(死亡)", "《标题》", "「内容」")) {
			assertTrue(!ModelAnswers.cleanTranslation(answer).isBlank(), answer);
		}
		assertEquals("水", ModelAnswers.cleanTranslation("{水}"));
		assertEquals("绵羊", ModelAnswers.cleanTranslation("<绵羊>"));
		assertEquals("海洋", ModelAnswers.cleanTranslation("[海洋]"));
		assertEquals("死亡", ModelAnswers.cleanTranslation("(死亡)"));
	}

	@Test
	void diagnosticOutputIsNeverDisplayedAsATranslation() {
		assertNull(ModelAnswers.parsePlain("[12:34:56] [INFO] AI Translate response", "Text"));
		assertNull(ModelAnswers.parsePlain("2026-09-22 12:34:56 ERROR request failed", "Text"));
		assertNull(ModelAnswers.parsePlain("{\"timestamp\":123,\"level\":\"INFO\",\"message\":\"ok\"}", "Text"));
	}
}
