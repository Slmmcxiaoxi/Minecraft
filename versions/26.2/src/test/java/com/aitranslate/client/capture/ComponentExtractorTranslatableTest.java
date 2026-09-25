package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * {@code /me}, {@code /tell}, {@code /msg} and {@code /say} produce messages that
 * wrap the sender and the body in a {@code translatable} component. The key must
 * stay untouched (it is a language key), but the arguments have to be walked -
 * that is what made those command outputs stay untranslated before.
 */
class ComponentExtractorTranslatableTest {

	@Test
	void translatableArgumentsAreCollected() {
		Component message = Component.translatable("chat.type.text",
				Component.literal("Steve"), Component.literal("Hello, traveller."));

		List<String> texts = ComponentExtractor.extract(message).texts();
		assertFalse(texts.contains("Steve"), "sender identity is structural data: " + texts);
		assertTrue(texts.contains("Hello, traveller."), texts.toString());
	}

	@Test
	void translatableKeyItselfIsNeverCollected() {
		Component message = Component.translatable("chat.type.text",
				Component.literal("Steve"), Component.literal("Hello"));
		List<String> texts = ComponentExtractor.extract(message).texts();
		assertFalse(texts.contains("chat.type.text"));
	}

	/**
	 * String arguments are only prose for chat keys (fourth feedback round). This
	 * test used to assert the opposite - a scoreboard argument was collected - which
	 * is exactly how "命令已设置: tell xxx Hello" ended up being translated.
	 */
	@Test
	void stringArgumentsOfCommandKeysAreNotCollected() {
		Component message = Component.translatable("commands.scoreboard.players.set.success",
				"aitrans_demo", "#ChapterOne");
		List<String> texts = ComponentExtractor.extract(message).texts();
		assertFalse(texts.contains("aitrans_demo"), texts.toString());
		assertFalse(texts.contains("#ChapterOne"), texts.toString());
	}

	@Test
	void stringArgumentsOfChatKeysAreCollected() {
		Component message = Component.translatable("chat.type.text", "Steve", "Hello, traveller.");
		List<String> texts = ComponentExtractor.extract(message).texts();
		assertTrue(texts.contains("Hello, traveller."), texts.toString());
	}

	@Test
	void rebuildKeepsKeyAndTranslatesArguments() {
		Component message = Component.translatable("chat.type.text",
				Component.literal("Steve"), Component.literal("Hello, traveller."));
		Component translated = ComponentExtractor.rebuild(message, Map.of("Hello, traveller.", "旅行者你好。"));

		assertTrue(translated.getContents() instanceof TranslatableContents);
		TranslatableContents contents = (TranslatableContents) translated.getContents();
		assertEquals("chat.type.text", contents.getKey());
		Object[] args = contents.getArgs();
		assertEquals(Component.literal("Steve").getContents(), ((Component) args[0]).getContents());
		assertEquals("旅行者你好。",
				((net.minecraft.network.chat.contents.PlainTextContents) ((Component) args[1]).getContents()).text());
	}

	@Test
	void hasTranslationDetectsArguments() {
		Component message = Component.translatable("chat.type.text",
				Component.literal("Steve"), Component.literal("Hello"));
		assertTrue(ComponentExtractor.hasTranslation(message, Map.of("Hello", "你好")));
		// Sender identity is never extracted, so even a stale/hostile cache entry cannot
		// rewrite it before PlayerList has refreshed.
		assertFalse(ComponentExtractor.hasTranslation(message, Map.of("Steve", "史蒂夫")));
		assertFalse(ComponentExtractor.hasTranslation(message, Map.of("Steve", "Steve")));
	}

	@Test
	void nestedTranslatableArgumentsAreWalked() {
		Component inner = Component.translatable("chat.type.text", Component.literal("Body text"));
		Component outer = Component.literal("prefix ").append(inner);
		List<String> texts = ComponentExtractor.extract(outer).texts();
		// Since the thirteenth round the key of a leaf is the text without its
		// surrounding whitespace (the whitespace is put back at render time), so that
		// "prefix " and "prefix", or "<Death> " and "<Death>", share one cache entry.
		assertTrue(texts.contains("prefix"), texts.toString());
		assertTrue(texts.contains("Body text"), texts.toString());
	}
}
