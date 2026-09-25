package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Serialised text components stored in NBT strings ("{"text":"..."}") are very
 * common in maps; they must be translated through their inner text instead of
 * being treated as one opaque literal.
 */
class ComponentExtractorJsonTest {

	@Test
	void plainTextIsReturnedAsIs() {
		assertEquals("Hello", ComponentExtractor.translatableText("Hello"));
		// without a translation the text is passed through unchanged
		assertEquals("Hello", ComponentExtractor.applyTranslation("Hello", java.util.Map.of()));
		assertEquals("你好", ComponentExtractor.applyTranslation("Hello", java.util.Map.of("Hello", "你好")));
	}

	@Test
	void jsonComponentIsTranslatedThroughItsInnerText() {
		String raw = "{\"text\":\"Shadow Stalker\",\"color\":\"red\",\"bold\":true}";
		assertEquals("Shadow Stalker", ComponentExtractor.translatableText(raw));

		String translated = ComponentExtractor.applyTranslation(raw, Map.of("Shadow Stalker", "暗影潜行者"));
		assertTrue(translated.contains("暗影潜行者"));
		assertTrue(translated.contains("\"color\":\"red\""), "colour must be preserved: " + translated);
		assertTrue(translated.contains("\"bold\":true"), "bold must be preserved: " + translated);
	}

	@Test
	void jsonComponentWithoutTranslationIsUnchanged() {
		String raw = "{\"text\":\"Danger\"}";
		assertEquals(raw, ComponentExtractor.applyTranslation(raw, Map.of("Other", "其他")));
	}

	@Test
	void jsonExtraSiblingsAreTranslated() {
		String raw = "{\"text\":\"Beware\",\"extra\":[{\"text\":\" the dark\"}]}";
		// The key of " the dark" is "the dark" since the thirteenth round: surrounding
		// whitespace belongs to the layout, not to the text that is translated.
		String translated = ComponentExtractor.applyTranslation(raw,
				Map.of("Beware", "当心", "the dark", "黑暗"));
		assertTrue(translated.contains("当心"), translated);
		assertTrue(translated.contains("黑暗"), translated);
		// and the whitespace of the original is preserved in the rebuilt JSON
		assertTrue(translated.contains("\" the dark\"".replace("the dark", "黑暗")), translated);
	}

	@Test
	void jsonArrayFormIsTranslated() {
		String raw = "[\"Chapter One\",{\"text\":\"The night falls\"}]";
		assertEquals("Chapter One", ComponentExtractor.translatableText(raw));
		String translated = ComponentExtractor.applyTranslation(raw, Map.of("Chapter One", "第一章"));
		assertTrue(translated.contains("第一章"));
	}

	@Test
	void brokenJsonFallsBackToPlainText() {
		String raw = "{\"text\": broken";
		assertEquals(raw, ComponentExtractor.translatableText(raw));
		// not valid JSON: the text is used as an ordinary key
		assertEquals("x", ComponentExtractor.applyTranslation(raw, Map.of(raw, "x")));
		assertEquals(raw, ComponentExtractor.applyTranslation(raw, Map.of()));
	}

	@Test
	void plainBracesWithoutComponentKeyAreNotTreatedAsJson() {
		// Not JSON: the braces are symbols around a text, so the text inside them is
		// the key (twelfth feedback round) - the braces themselves are put back at
		// render time and are never translated.
		assertEquals("not a component", ComponentExtractor.translatableText("{not a component}"));
		assertEquals("{不是组件}", ComponentExtractor.applyTranslation("{not a component}",
				Map.of("not a component", "不是组件")));
	}

	@Test
	void serialisedDataWithAQuotedKeyIsLeftWhole() {
		// A brace/bracket text that carries a quoted key is a data structure, not a
		// sentence in symbols: its syntax must survive.
		assertEquals("{\"foo\": 1}", ComponentExtractor.translatableText("{\"foo\": 1}"));
		assertEquals("[\"foo\": 1]", ComponentExtractor.translatableText("[\"foo\": 1]"));
	}

	@Test
	void nullAndEmptyAreSafe() {
		assertEquals(null, ComponentExtractor.translatableText(null));
		assertEquals("", ComponentExtractor.translatableText(""));
		assertFalse(ComponentExtractor.translatableText("x").isEmpty());
	}
}
