package com.aitranslate.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlaceholderUtilTest {

	@Test
	void extractsAndValidatesFormatPlaceholders() {
		String original = "Hello %s, you have %1$s coins (%d)";
		List<String> placeholders = PlaceholderUtil.extract(original);
		assertTrue(placeholders.contains("%s"));
		assertTrue(placeholders.contains("%1$s"));
		assertTrue(placeholders.contains("%d"));
		assertTrue(PlaceholderUtil.validate(original, "你好 %s，你有 %1$s 枚硬币 (%d)"));
		assertFalse(PlaceholderUtil.validate(original, "你好，你有硬币"));
	}

	@Test
	void onlyNumericBraceFormsArePlaceholders() {
		List<String> placeholders = PlaceholderUtil.extract("{water} {0} {1:%.2f} <world>");
		assertEquals(List.of("{0}", "{1:%.2f}"), placeholders);
		assertTrue(PlaceholderUtil.validate("{water} and {0}", "{水} 与 {0}"));
		assertFalse(PlaceholderUtil.validate("value {1:%.2f}", "值"));
		assertFalse(PlaceholderUtil.isFullTextPlaceholder("{water}"));
		assertTrue(PlaceholderUtil.isFullTextPlaceholder("{0}"));
		assertTrue(PlaceholderUtil.isFullTextPlaceholder("{1:%.2f}"));
	}

	@Test
	void treatsEscapedPercentAsText() {
		assertTrue(PlaceholderUtil.extract("100%% sure").stream().noneMatch("%%"::equals));
	}

	@Test
	void detectsPlaceholderOnlyText() {
		assertTrue(PlaceholderUtil.isPlaceholderOnly("%s"));
		assertTrue(PlaceholderUtil.isPlaceholderOnly("  %1$s - %2$s {0}  "));
		assertTrue(PlaceholderUtil.isPlaceholderOnly("123 - 456"));
		assertFalse(PlaceholderUtil.isPlaceholderOnly("Hello %s"));
	}

	@Test
	void rejectsTranslationsWithMarkdownFences() {
		assertFalse(PlaceholderUtil.validate("Hello", "```你好```"));
		assertTrue(PlaceholderUtil.validate("Hello", "你好"));
	}

	@Test
	void rejectsBlankTranslations() {
		assertFalse(PlaceholderUtil.validate("Hello", "   "));
		assertFalse(PlaceholderUtil.validate("Hello", null));
	}

	@Test
	void keepsEmptyPlaceholderListValid() {
		assertTrue(PlaceholderUtil.validate("Hello", "你好"));
		assertEquals(0, PlaceholderUtil.extract("").size());
	}
}
