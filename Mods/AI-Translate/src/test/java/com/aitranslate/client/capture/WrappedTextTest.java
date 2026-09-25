package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Symbol wrapped text (twelfth feedback round).
 * <p>
 * The report was specific: {@code [Death]} was translated, {@code <Death>} was not.
 * The reason was the placeholder filter - anything shaped like {@code <identifier>}
 * had to survive byte for byte, which is right for {@code <name>} and wrong for
 * {@code <Death>}. Taking the symbols off first (this test's subject) makes the two
 * cases behave the same way while keeping the placeholder conventions.
 */
class WrappedTextTest {

	@AfterEach
	void restoreDefaults() {
		ComponentExtractor.setTranslateWrappedText(true);
	}

	@Test
	void angleBracketsAreUnwrappedAndPutBack() {
		WrappedText.Parts parts = WrappedText.split("<Death>");
		assertEquals("<", parts.prefix());
		assertEquals("Death", parts.inner());
		assertEquals(">", parts.suffix());
		assertEquals("<死亡>", parts.rewrap("死亡"));
	}

	@Test
	void everyCommonSymbolPairIsSupported() {
		assertEquals(new WrappedText.Parts("[", "Kills", "]"), WrappedText.split("[Kills]"));
		assertEquals(new WrappedText.Parts("(", "Welcome", ")"), WrappedText.split("(Welcome)"));
		assertEquals(new WrappedText.Parts("{", "Death", "}"), WrappedText.split("{Death}"));
		assertEquals(new WrappedText.Parts("\"", "Death", "\""), WrappedText.split("\"Death\""));
		assertEquals(new WrappedText.Parts("\u300a", "Death", "\u300b"), WrappedText.split("\u300aDeath\u300b"));
		assertEquals(new WrappedText.Parts("\u3010", "Death", "\u3011"), WrappedText.split("\u3010Death\u3011"));
		assertEquals(new WrappedText.Parts("\u3008", "Death", "\u3009"), WrappedText.split("\u3008Death\u3009"));
		assertEquals(new WrappedText.Parts("\uff08", "Welcome", "\uff09"),
				WrappedText.split("\uff08Welcome\uff09"));
	}

	@Test
	void nestedSymbolsAreAllTakenOff() {
		WrappedText.Parts parts = WrappedText.split("<[Death]>");
		assertEquals("<[", parts.prefix());
		assertEquals("Death", parts.inner());
		assertEquals("]>", parts.suffix());
		assertEquals("<[死亡]>", parts.rewrap("死亡"));
	}

	@Test
	void deeplyNestedSymbolsKeepTheirOrder() {
		WrappedText.Parts parts = WrappedText.split("<[{(Death)}]>");
		assertEquals("<[{(", parts.prefix());
		assertEquals("Death", parts.inner());
		assertEquals(")}]>", parts.suffix());
		assertEquals("<[{(死亡)}]>", parts.rewrap("死亡"));
	}

	@Test
	void aSingleSidedSymbolIsKept() {
		assertEquals(new WrappedText.Parts("<", "Death", ""), WrappedText.split("<Death"));
		assertEquals(new WrappedText.Parts("", "Death", ">"), WrappedText.split("Death>"));
		assertEquals("<死亡", WrappedText.split("<Death").rewrap("死亡"));
	}

	@Test
	void aBracketThatClosesEarlyIsNotAWrapper() {
		// The "]" belongs to the first word, not to the whole line: translating the
		// rest of the sentence on its own would move the boundary into the middle of
		// the text.
		assertFalse(WrappedText.split("[Quest] Kill the dragon").isWrapped());
		assertFalse(WrappedText.split("<a> and <b>").isWrapped());
	}

	@Test
	void ordinaryProseIsNeverTouched() {
		assertFalse(WrappedText.split("Firefly's eyes sharpened.").isWrapped());
		assertFalse(WrappedText.split("don't stop").isWrapped());
		assertFalse(WrappedText.split("Hello, adventurer!").isWrapped());
		assertFalse(WrappedText.split("the travellers' ledger").isWrapped());
		assertFalse(WrappedText.split("").isWrapped());
		assertFalse(WrappedText.split("<>").isWrapped());
	}

	@Test
	void quotationMarksAroundAWholeSentenceAreUnwrapped() {
		WrappedText.Parts parts = WrappedText.split("\u201cClose your eyes,\u201d");
		assertEquals("\u201c", parts.prefix());
		assertEquals("Close your eyes,", parts.inner());
		assertEquals("\u201d", parts.suffix());
	}

	// ------------------------------------------------------- the extractor

	@Test
	void theInnerTextIsWhatGetsTranslated() {
		assertEquals("Death", ComponentExtractor.translatableText("<Death>"));
		assertEquals("Kills", ComponentExtractor.translatableText("[Kills]"));
		assertEquals("Welcome", ComponentExtractor.translatableText("(Welcome)"));
		assertEquals("Death", ComponentExtractor.translatableText("<[Death]>"));
	}

	@Test
	void placeholderMarkersStayWhole() {
		// Placeholders are recognised by syntax only (fourteenth feedback round): the
		// printf shapes, a number between braces, and the ${...} form.
		assertEquals("%s", ComponentExtractor.translatableText("%s"));
		assertEquals("{0}", ComponentExtractor.translatableText("{0}"));
		assertEquals("{1:%.2f}", ComponentExtractor.translatableText("{1:%.2f}"));
		assertEquals("${gold}", ComponentExtractor.translatableText("${gold}"));
		// A lowercase word in brackets is a word, not a placeholder: the case of the
		// text must never decide whether it is translated (that rule is what left
		// "<death>" untranslated while "<Death>" worked).
		assertEquals("name", ComponentExtractor.translatableText("<name>"));
		assertEquals("player_1", ComponentExtractor.translatableText("<player_1>"));
		assertEquals("death", ComponentExtractor.translatableText("<death>"));
		assertEquals("water", ComponentExtractor.translatableText("{water}"));
	}

	@Test
	void textThatMerelyStartsWithABracketStaysWhole() {
		assertEquals("[Quest] Kill the dragon", ComponentExtractor.translatableText("[Quest] Kill the dragon"));
	}

	@Test
	void theSymbolsArePutBackAroundTheTranslation() {
		assertEquals("<死亡>", ComponentExtractor.applyTranslation("<Death>", Map.of("Death", "死亡")));
		assertEquals("[击杀数]", ComponentExtractor.applyTranslation("[Kills]", Map.of("Kills", "击杀数")));
		assertEquals("<[死亡]>", ComponentExtractor.applyTranslation("<[Death]>", Map.of("Death", "死亡")));
		assertEquals("{水}", ComponentExtractor.applyTranslation("{water}", Map.of("water", "水")));
		assertEquals("{死亡}", ComponentExtractor.applyTranslation("{death}", Map.of("death", "死亡")));
		assertEquals("{绵羊}", ComponentExtractor.applyTranslation("{sheep}", Map.of("sheep", "绵羊")));
	}

	@Test
	void aTranslationForTheWrongTextKeepsTheOriginal() {
		assertEquals("<Death>", ComponentExtractor.applyTranslation("<Death>", Map.of("Kills", "击杀数")));
	}

	@Test
	void aRebuiltComponentKeepsTheSymbols() {
		net.minecraft.network.chat.Component original = net.minecraft.network.chat.Component.literal("<Death>");
		assertEquals("<死亡>", ComponentExtractor.rebuild(original, Map.of("Death", "死亡")).getString());
		assertEquals("[击杀数]",
				ComponentExtractor.rebuild(net.minecraft.network.chat.Component.literal("[Kills]"),
						Map.of("Kills", "击杀数")).getString());
	}

	@Test
	void theSwitchRestoresTheOldBehaviour() {
		ComponentExtractor.setTranslateWrappedText(false);
		assertEquals("<Death>", ComponentExtractor.translatableText("<Death>"));
		assertEquals("[Kills]", ComponentExtractor.translatableText("[Kills]"));
		assertTrue(ComponentExtractor.translateWrappedText() == false);
	}

	@Test
	void serialisedComponentsStillWork() {
		// The JSON form is handled before the symbols are looked at: "{\"text\":…}"
		// is not a "{}" wrapper.
		assertEquals("Shadow Stalker",
				ComponentExtractor.translatableText("{\"text\":\"Shadow Stalker\",\"color\":\"red\"}"));
		assertEquals("{\"text\":\"暗影潜行者\",\"color\":\"red\"}",
				ComponentExtractor.applyTranslation("{\"text\":\"Shadow Stalker\",\"color\":\"red\"}",
						Map.of("Shadow Stalker", "暗影潜行者")));
	}
}
