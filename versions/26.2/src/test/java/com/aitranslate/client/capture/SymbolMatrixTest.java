package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.aitranslate.client.scheduler.TextDetector;

/**
 * Every path that turns a literal into a translation key, and every path that puts
 * the result back (thirteenth feedback round).
 * <p>
 * The twelfth round fixed symbol wrapped text in the plain-literal path only and the
 * report came back: {@code <Death>} still stayed English while {@code [Death]} was
 * translated. Two holes remained, and both are covered here:
 * <ul>
 * <li>the inner text of a serialised component ({@code {"text":"<Death>"}}) was
 * returned unchanged, so the placeholder filter still recognised
 * {@code <Death>};</li>
 * <li>a literal with surrounding whitespace ({@code "<Death> "} - chat lines, book
 * pages scores are full of them) was not recognised as wrapped at all, and the
 * filter trims before it looks.</li>
 * </ul>
 * The test is written as a table because the point is completeness: whatever the
 * container and whatever the symbols, the key must be {@code Death} and the rendered
 * result must carry the original symbols and whitespace.
 */
class SymbolMatrixTest {

	private static final Map<String, String> TRANSLATIONS = Map.of("Death", "死亡", "Kills", "击杀数",
			"Welcome", "欢迎");

	@AfterEach
	void restore() {
		ComponentExtractor.setTranslateWrappedText(true);
	}

	/** Renders a literal the way the render layer does and returns the plain text. */
	private static String rendered(String raw) {
		return ComponentExtractor.rebuild(net.minecraft.network.chat.Component.literal(raw), TRANSLATIONS)
				.getString();
	}

	// ------------------------------------------------ plain literals, all shapes

	@Test
	void plainLiteralsOfEveryShapeAreUnwrappedAndRebuilt() {
		assertEquals("Death", ComponentExtractor.translatableText("<Death>"));
		assertEquals("<死亡>", rendered("<Death>"));

		assertEquals("Kills", ComponentExtractor.translatableText("[Kills]"));
		assertEquals("[击杀数]", rendered("[Kills]"));

		assertEquals("Welcome", ComponentExtractor.translatableText("(Welcome)"));
		assertEquals("(欢迎)", rendered("(Welcome)"));

		assertEquals("Death", ComponentExtractor.translatableText("{Death}"));
		assertEquals("{死亡}", rendered("{Death}"));

		assertEquals("Death", ComponentExtractor.translatableText("\"Death\""));
		assertEquals("\"死亡\"", rendered("\"Death\""));

		assertEquals("Death", ComponentExtractor.translatableText("<[Death]>"));
		assertEquals("<[死亡]>", rendered("<[Death]>"));
	}

	@Test
	void surroundingWhitespaceIsKeptOutsideTheSymbols() {
		// The reported case behind "still broken": the trailing space made the text
		// look like it merely started with a bracket, and the trimmed placeholder
		// check then skipped it.
		assertEquals("Death", ComponentExtractor.translatableText("<Death> "));
		assertEquals("<死亡> ", rendered("<Death> "));

		assertEquals("Death", ComponentExtractor.translatableText(" <Death>"));
		assertEquals(" <死亡>", rendered(" <Death>"));

		assertEquals("Death", ComponentExtractor.translatableText("  <Death>  "));
		assertEquals("  <死亡>  ", rendered("  <Death>  "));

		assertEquals("Death", ComponentExtractor.translatableText("<Death>\n"));
		assertEquals("<死亡>\n", rendered("<Death>\n"));

		assertEquals("Death", ComponentExtractor.translatableText("\n  <[Death]>  \n"));
		assertEquals("\n  <[死亡]>  \n", rendered("\n  <[Death]>  \n"));
	}

	@Test
	void singleSidedSymbolsAreStillHandled() {
		assertEquals("Death", ComponentExtractor.translatableText("<Death"));
		assertEquals("<死亡", rendered("<Death"));
		assertEquals("Death", ComponentExtractor.translatableText("Death>"));
		assertEquals("死亡>", rendered("Death>"));
		assertEquals("Death", ComponentExtractor.translatableText(" <Death"));
		assertEquals(" <死亡", rendered(" <Death"));
	}

	// ------------------------------------------------ serialised components

	@Test
	void serialisedComponentsAreUnwrappedToo() {
		// The second hole of the twelfth round: same text, JSON container.
		String json = "{\"text\":\"<Death>\"}";
		assertEquals("Death", ComponentExtractor.translatableText(json));
		assertEquals("{\"text\":\"<死亡>\"}", ComponentExtractor.applyTranslation(json, TRANSLATIONS));

		assertEquals("{\"text\":\"[击杀数]\"}",
				ComponentExtractor.applyTranslation("{\"text\":\"[Kills]\"}", TRANSLATIONS));
		assertEquals("{\"text\":\"(欢迎)\"}",
				ComponentExtractor.applyTranslation("{\"text\":\"(Welcome)\"}", TRANSLATIONS));
	}

	@Test
	void serialisedComponentsKeepStylesAndWhitespace() {
		String json = "{\"text\":\"<Death> \",\"color\":\"red\",\"bold\":true}";
		assertEquals("Death", ComponentExtractor.translatableText(json));
		assertEquals("{\"text\":\"<死亡> \",\"color\":\"red\",\"bold\":true}",
				ComponentExtractor.applyTranslation(json, TRANSLATIONS));
	}

	@Test
	void serialisedComponentsWithExtraAreUnwrapped() {
		String json = "{\"text\":\"\",\"extra\":[{\"text\":\"<Death>\"},{\"text\":\" approaches\"}]}";
		assertEquals("Death", ComponentExtractor.translatableText(json), "first leaf is the key");
		String translated = ComponentExtractor.applyTranslation(json, TRANSLATIONS);
		assertEquals("{\"text\":\"\",\"extra\":[{\"text\":\"<死亡>\"},{\"text\":\" approaches\"}]}", translated);
	}

	@Test
	void jsonArraysOfStringsAreUnwrapped() {
		String json = "[\"<Death>\"]";
		assertEquals("Death", ComponentExtractor.translatableText(json));
		assertEquals("[\"<死亡>\"]", ComponentExtractor.applyTranslation(json, TRANSLATIONS));
	}

	// ------------------------------------------------ the key must survive the filter

	@Test
	void everyUnwrappedKeyPassesTheTextFilter() {
		TextDetector detector = new TextDetector();
		String[] raws = { "<Death>", "<Death> ", " <Death>", "<[Death]>", "<Death>\n", "[Kills]", "(Welcome)",
				"{Death}", "\"Death\"", "{\"text\":\"<Death>\"}" };
		for (String raw : raws) {
			String key = ComponentExtractor.translatableText(raw);
			assertNull(detector.skipReason(key), "raw '" + raw + "' produced key '" + key + "'");
		}
	}

	@Test
	void placeholderMarkersAndPlayerNamesStillStayUntranslated() {
		TextDetector detector = new TextDetector();
		// Placeholders by syntax stay whole and are skipped.
		assertEquals("{0}", ComponentExtractor.translatableText("{0}"));
		assertEquals("%s", ComponentExtractor.translatableText("%s"));
		assertNotNull(detector.skipReason("{0}"), "a brace placeholder is never requested");
		// A bracketed word is a word, whatever its case (fourteenth feedback round):
		// <name> used to be kept whole because it is lowercase, which is exactly the
		// rule that left <death> untranslated.
		assertEquals("name", ComponentExtractor.translatableText("<name>"));
		assertNull(detector.skipReason(ComponentExtractor.translatableText("<name>")));
		// A real player name is recognised by the detector, not by the extractor.
		detector.setPlayerNames(java.util.Set.of("steve"));
		assertEquals("player name", detector.skipReason(ComponentExtractor.translatableText("<Steve>")));
		assertEquals("player name", detector.skipReason(ComponentExtractor.translatableText("<Steve> ")));
	}

	@Test
	void theCaseOfTheTextNeverDecidesWhetherItIsTranslated() {
		// The reported bug: <Death> translated, <death> did not.
		for (String word : new String[] { "Death", "death", "DEATH", "dEaTh", "DeAtH" }) {
			for (String[] pair : new String[][] { { "<", ">" }, { "[", "]" }, { "(", ")" }, { "{", "}" },
					{ "\"", "\"" } }) {
				String raw = pair[0] + word + pair[1];
				String key = ComponentExtractor.translatableText(raw);
				assertEquals(word, key, "raw " + raw);
				assertNull(new TextDetector().skipReason(key), "raw " + raw);
				// The model answers per word, so the map is built per word here.
				String translated = ComponentExtractor.applyTranslation(raw, Map.of(word, "死亡"));
				assertEquals(pair[0] + "死亡" + pair[1], translated, "raw " + raw);
			}
		}
	}

	@Test
	void aTranslationForAnotherTextChangesNothing() {
		assertEquals("<Death>", ComponentExtractor.applyTranslation("<Death>", Map.of("Kills", "击杀数")));
		assertEquals("{\"text\":\"<Death>\"}",
				ComponentExtractor.applyTranslation("{\"text\":\"<Death>\"}", Map.of("Kills", "击杀数")));
	}

	@Test
	void theSwitchTurnsTheWholeFeatureOff() {
		ComponentExtractor.setTranslateWrappedText(false);
		assertEquals("<Death>", ComponentExtractor.translatableText("<Death>"));
		assertEquals("<Death> ", ComponentExtractor.translatableText("<Death> "));
		assertEquals("{\"text\":\"<Death>\"}",
				ComponentExtractor.applyTranslation("{\"text\":\"<Death>\"}", TRANSLATIONS));
	}
}
