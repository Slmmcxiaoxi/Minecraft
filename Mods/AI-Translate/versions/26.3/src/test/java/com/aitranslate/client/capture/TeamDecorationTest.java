package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.config.ModConfig;
import com.aitranslate.client.scheduler.TextDetector;
import com.aitranslate.client.scheduler.TranslationScheduler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Scoreboard;

/**
 * Scoreboard team decorations (fifteenth feedback round).
 * <p>
 * Reported problem: "the team prefix and suffix are not translated again". The
 * component the game builds for a decorated name is
 * {@code Component.empty().append(prefix).append(name).append(suffix)}
 * ({@code PlayerTeam#getFormattedName}), so the three parts are separate children and
 * the extractor sees them as three texts. This test pins that shape, and pins the
 * decision that follows from it: the prefix and the suffix are map text and have to be
 * translated, while the player name in the middle stays untouched.
 * <p>
 * The prefix in the reported case was {@code [Guardian] } - "Guardian" is the name of
 * a vanilla mob, and the vanilla-value filter that used to sit in {@link TextDetector}
 * skipped it for that reason.
 */
class TeamDecorationTest {

	private static Component decoratedName(String prefix, String name, String suffix) {
		return Component.empty()
				.append(Component.literal(prefix))
				.append(Component.literal(name))
				.append(Component.literal(suffix));
	}

	@Test
	void standalonePlayerTagExposesOnlyItsIdentityForProtection() {
		Component tag = decoratedName("[Winner] ", "Slmmcxiaoxi", " <Loser>");
		assertEquals("Slmmcxiaoxi", ComponentExtractor.playerIdentity(tag));
	}

	@Test
	void realVanillaTeamComponentUsesTheSameThreePartTranslationPath() {
		var team = new Scoreboard().addPlayerTeam("test");
		team.setPlayerPrefix(Component.literal("[Winner]"));
		team.setPlayerSuffix(Component.literal("<Loser>"));
		Component decorated = team.getFormattedName(Component.literal("Slmmcxiaoxi"));
		Component leave = Component.translatable("multiplayer.player.left", decorated);

		assertEquals("Slmmcxiaoxi", ComponentExtractor.playerIdentity(decorated));
		assertEquals(List.of("Winner", "Loser"), ComponentExtractor.uniqueTexts(leave));
		Component rebuilt = ComponentExtractor.rebuild(leave,
				java.util.Map.of("Winner", "赢家", "Slmmcxiaoxi", "错误名字", "Loser", "输家"));
		assertTrue(rebuilt.getString().contains("[赢家]Slmmcxiaoxi<输家>"), rebuilt.getString());
		assertFalse(rebuilt.getString().contains("错误名字"), rebuilt.getString());
	}

	@Test
	void teamPartsAreExtractedAsSeparateTexts() {
		List<String> texts = ComponentExtractor.extract(decoratedName("[Guardian] ", "Steve", " the Bold")).texts();

		// The extractor hands over the word inside the decoration; the symbols and the
		// surrounding whitespace are re-attached when the translation is rendered
		// (WrappedText), which is what keeps "[Guardian]" looking like "[Guardian]".
		assertTrue(texts.contains("Guardian"), "the team prefix has to be a translation candidate");
		assertTrue(texts.contains("Steve"), "the name is a candidate too - the detector is what keeps it out");
		assertTrue(texts.contains("the Bold"), "the team suffix has to be a translation candidate");
	}

	@Test
	void theDetectorTranslatesThePrefixAndSuffixButNotTheName() {
		TextDetector detector = new TextDetector();
		detector.setPlayerNames(java.util.Set.of("Steve"));

		List<String> texts = ComponentExtractor.extract(decoratedName("[Guardian] ", "Steve", " the Bold")).texts();
		List<String> translatable = texts.stream().filter(detector::shouldTranslate).toList();

		assertTrue(translatable.contains("Guardian"),
				"the reported prefix `[Guardian]` must be translated (it matches a vanilla mob name, "
						+ "which used to skip it), got " + translatable);
		assertTrue(translatable.contains("the Bold"), "the suffix must be translated, got " + translatable);
		assertFalse(translatable.contains("Steve"), "the real player name must never be translated");
	}

	@Test
	void wrappedTeamPartsKeepTheirSymbols() {
		// The rendered form: the symbols of the prefix come back around the translated word.
		var parts = WrappedText.split("[Guardian] ");
		assertTrue(parts.isWrapped(), "'[Guardian] ' is recognised as symbol wrapped text");
		assertEquals("Guardian", parts.inner());
		assertEquals("[守卫者] ", parts.rewrap("守卫者"));
	}

	@Test
	void aVanillaWordInAMapIsStillMapText() {
		TextDetector detector = new TextDetector();
		// The words below are all values of the vanilla language file; a literal copy of
		// them in a map is not localised by the game and therefore has to be translated.
		for (String word : List.of("Guardian", "Stone", "Diamond Sword", "the Brave", "Village")) {
			assertTrue(detector.shouldTranslate(word), "'" + word + "' is map text and must be translated");
		}
	}

	@Test
	void serverFlattenedDecorationStillProtectsThePlayerIdentity() {
		TranslationScheduler previous = AITranslateModClient.scheduler;
		TranslationScheduler scheduler = new TranslationScheduler(new ModConfig(), null, null);
		AITranslateModClient.scheduler = scheduler;
		try {
			scheduler.detector().setPlayerNames(java.util.Set.of("Slmmcxiaoxi"));
			Component flattened = Component.literal("[Winner]Slmmcxiaoxi<Loser>");

			assertEquals(List.of("Winner", "Loser"), ComponentExtractor.uniqueTexts(flattened));
			Component rebuilt = ComponentExtractor.rebuild(flattened,
					java.util.Map.of("Winner", "赢家", "Slmmcxiaoxi", "错误名字", "Loser", "输家"));
			assertEquals("[赢家]Slmmcxiaoxi<输家>", rebuilt.getString());
		} finally {
			AITranslateModClient.scheduler = previous;
		}
	}

	@Test
	void completeServerFlattenedChatSplitsPrefixSuffixAndBodyWithoutPlayerList() {
		TranslationScheduler previous = AITranslateModClient.scheduler;
		AITranslateModClient.scheduler = new TranslationScheduler(new ModConfig(), null, null);
		try {
			Component chat = Component.literal("<[Winner]Slmmcxiaoxi<Loser>>Hello");
			assertEquals(List.of("Winner", "Loser", "Hello"), ComponentExtractor.uniqueTexts(chat));
			Component rebuilt = ComponentExtractor.rebuild(chat,
					java.util.Map.of("Winner", "胜者", "Loser", "败者", "Hello", "你好",
							"Slmmcxiaoxi", "错误名字"));
			assertEquals("<[胜者]Slmmcxiaoxi<败者>>你好", rebuilt.getString());
		} finally {
			AITranslateModClient.scheduler = previous;
		}
	}

	@Test
	void flattenedSenderInsideVanillaChatArgumentDoesNotGetSkippedAsAPlayerField() {
		TranslationScheduler previous = AITranslateModClient.scheduler;
		AITranslateModClient.scheduler = new TranslationScheduler(new ModConfig(), null, null);
		try {
			Component sender = Component.literal("[Winner]Slmmcxiaoxi<Loser>");
			Component chat = Component.translatable("chat.type.text", sender, Component.literal("Hello"));
			assertEquals(List.of("Winner", "Loser", "Hello"), ComponentExtractor.uniqueTexts(chat));
			Component rebuilt = ComponentExtractor.rebuild(chat,
					java.util.Map.of("Winner", "胜者", "Loser", "败者", "Hello", "你好"));
			assertTrue(rebuilt.getString().contains("[胜者]Slmmcxiaoxi<败者>"), rebuilt.getString());
			assertTrue(rebuilt.getString().contains("你好"), rebuilt.getString());
		} finally {
			AITranslateModClient.scheduler = previous;
		}
	}

	@Test
	void realMultiplayerPacketWrapperDoesNotCrashAndKeepsSenderStyle() {
		TranslationScheduler previous = AITranslateModClient.scheduler;
		AITranslateModClient.scheduler = new TranslationScheduler(new ModConfig(), null, null);
		try {
			Component inner = Component.empty()
					.append(Component.literal("[Winner]"))
					.append(Component.literal("Slmmcxiaoxi"))
					.append(Component.literal("<Loser>"));
			Component packetName = Component.empty().withStyle(net.minecraft.ChatFormatting.GOLD).append(inner);
			Component chat = Component.translatable("chat.type.text", packetName, Component.literal("Hello"));

			assertEquals(List.of("Winner", "Loser", "Hello"), ComponentExtractor.uniqueTexts(chat));
			Component rebuilt = ComponentExtractor.rebuild(chat,
					java.util.Map.of("Winner", "胜者", "Loser", "败者", "Hello", "你好"));
			assertTrue(rebuilt.getString().contains("[胜者]Slmmcxiaoxi<败者>"), rebuilt.getString());
			assertTrue(rebuilt.getString().contains("你好"), rebuilt.getString());
			Object rebuiltSender = ((net.minecraft.network.chat.contents.TranslatableContents) rebuilt.getContents())
					.getArgs()[0];
			assertEquals(packetName.getStyle(), ((Component) rebuiltSender).getStyle(),
					"sender click/hover/colour style must survive display-only rebuilding");
		} finally {
			AITranslateModClient.scheduler = previous;
		}
	}
}
