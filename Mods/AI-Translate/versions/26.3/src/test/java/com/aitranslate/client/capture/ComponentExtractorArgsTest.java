package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.aitranslate.client.scheduler.VanillaText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Arguments of {@code translatable} nodes and vanilla names.
 * <p>
 * Fourth feedback round: {@code 命令已设置: tell xxx Hello} was translated into
 * {@code 命令已设置: 告诉xxx你好}. The message is a vanilla translatable whose
 * argument is the raw command, so treating every String argument as prose was the
 * bug. Only chat keys carry prose in their String arguments.
 */
class ComponentExtractorArgsTest {

	@AfterEach
	void resetFlags() {
		ComponentExtractor.setTranslateVanillaNames(false);
	}

	@Test
	void commandArgumentOfAVanillaMessageIsNotTranslated() {
		// This is exactly the reported message (advMode.setCommand.success).
		Component message = Component.translatable("advMode.setCommand.success", "tell xxx Hello");

		assertTrue(ComponentExtractor.uniqueTexts(message).isEmpty(),
				"the command inside a system message must not be sent to the translator");
	}

	@Test
	void componentArgumentsOfAnyKeyAreTranslated() {
		Component message = Component.translatable("commands.give.success",
				Component.literal("Diamond Sword"), "Steve");

		assertEquals(List.of("Diamond Sword"), ComponentExtractor.uniqueTexts(message));
	}

	@Test
	void giveAndDeathMessagesTranslateDecorationsButNotThePlayer() {
		Component decorated = Component.empty()
				.append(Component.literal("[Winner]"))
				.append(Component.literal("Slmmcxiaoxi"))
				.append(Component.literal("<Loser>"));
		Component give = Component.translatable("commands.give.success.single",
				Component.translatable("item.minecraft.diamond"), 1, decorated);
		Component death = Component.translatable("death.attack.generic", decorated);

		assertEquals(List.of("Winner", "Loser"), ComponentExtractor.uniqueTexts(give));
		assertEquals(List.of("Winner", "Loser"), ComponentExtractor.uniqueTexts(death));
		var translations = Map.of("Winner", "胜者", "Loser", "败者", "Slmmcxiaoxi", "错误名字");
		assertTrue(ComponentExtractor.rebuild(give, translations).getString().contains("[胜者]Slmmcxiaoxi<败者>"));
		assertTrue(ComponentExtractor.rebuild(death, translations).getString().contains("[胜者]Slmmcxiaoxi<败者>"));
	}

	@Test
	void dataCommandFeedbackIsEntirelyOpaque() {
		Component data = Component.translatable("commands.data.entity.query",
				Component.literal("Slmmcxiaoxi has the following entity data"),
				Component.literal("{CustomName:\"microwave\",Pos:[1.0d,64.0d,-3.0d]}"));
		assertTrue(ComponentExtractor.uniqueTexts(data).isEmpty());
		Component rebuilt = ComponentExtractor.rebuild(data,
				Map.of("Slmmcxiaoxi has the following entity data", "错误翻译"));
		assertFalse(rebuilt.getString().contains("错误翻译"));
	}

	@Test
	void chatArgumentsAreStillTranslated() {
		Component announcement = Component.translatable("chat.type.announcement", "Steve",
				"Meet me at the gate");
		Component emote = Component.translatable("chat.type.emote", "Steve", "draws a rusty sword");

		// Identity arguments are protected structurally; only the body is ever a
		// translation candidate, even before PlayerList has refreshed.
		assertEquals(List.of("Meet me at the gate"), ComponentExtractor.uniqueTexts(announcement));
		assertEquals(List.of("draws a rusty sword"), ComponentExtractor.uniqueTexts(emote));
	}

	@Test
	void teamChatAndPrivateMessagesProtectNamesButTranslateTheBody() {
		Component team = Component.translatable("chat.type.team.text",
				Component.literal("Red Team"), Component.literal("Steve"), Component.literal("Hello team"));
		Component direct = Component.translatable("commands.message.display.incoming",
				Component.literal("Alex"), Component.literal("Meet at spawn"));

		assertEquals(List.of("Red Team", "Hello team"), ComponentExtractor.uniqueTexts(team));
		assertEquals(List.of("Meet at spawn"), ComponentExtractor.uniqueTexts(direct));

		Component rebuilt = ComponentExtractor.rebuild(team,
				Map.of("Red Team", "红队", "Steve", "史蒂夫", "Hello team", "队友们好"));
		assertTrue(rebuilt.getString().contains("Steve"), rebuilt.getString());
		assertFalse(rebuilt.getString().contains("史蒂夫"), rebuilt.getString());
		assertTrue(rebuilt.getString().contains("红队"), rebuilt.getString());
		assertTrue(rebuilt.getString().contains("队友们好"), rebuilt.getString());
	}

	@Test
	void decoratedChatSenderTranslatesTeamPrefixAndSuffixButNotProfileName() {
		Component sender = Component.empty()
				.append(Component.literal("[Guild] "))
				.append(Component.literal("Steve"))
				.append(Component.literal(" the Brave"));
		Component chat = Component.translatable("chat.type.text", sender, Component.literal("Hello"));

		assertEquals(List.of("Guild", "the Brave", "Hello"), ComponentExtractor.uniqueTexts(chat));
		Component rebuilt = ComponentExtractor.rebuild(chat,
				Map.of("Guild", "公会", "Steve", "史蒂夫", "the Brave", "勇者", "Hello", "你好"));
		assertTrue(rebuilt.getString().contains("[公会] Steve 勇者"), rebuilt.getString());
		assertFalse(rebuilt.getString().contains("史蒂夫"), rebuilt.getString());
	}

	@Test
	void joinAndLeaveMessagesNeverExposePlayerNamesToTranslation() {
		Component joined = Component.translatable("multiplayer.player.joined", Component.literal("Notch"));
		Component left = Component.translatable("multiplayer.player.left", "Alex");
		assertTrue(ComponentExtractor.uniqueTexts(joined).isEmpty());
		assertTrue(ComponentExtractor.uniqueTexts(left).isEmpty());
	}

	@Test
	void joinAndLeaveTranslateDecorationsButNotTheProfileName() {
		Component decorated = Component.empty()
				.append(Component.literal("[Winner] "))
				.append(Component.literal("Slmmcxiaoxi"))
				.append(Component.literal(" <Loser>"));
		Component left = Component.translatable("multiplayer.player.left", decorated);

		assertEquals(List.of("Winner", "Loser"), ComponentExtractor.uniqueTexts(left));
		Component rebuilt = ComponentExtractor.rebuild(left,
				Map.of("Winner", "赢家", "Slmmcxiaoxi", "错误名字", "Loser", "败者"));
		assertTrue(rebuilt.getString().contains("[赢家] Slmmcxiaoxi <败者>"), rebuilt.getString());
		assertFalse(rebuilt.getString().contains("错误名字"), rebuilt.getString());
	}

	@Test
	void lockedContainerNameIsATranslationCandidate() {
		Component locked = Component.translatable("container.isLocked", Component.literal("microwave"));
		assertEquals(List.of("microwave"), ComponentExtractor.uniqueTexts(locked));
		Component rebuilt = ComponentExtractor.rebuild(locked, Map.of("microwave", "微波炉"));
		assertTrue(rebuilt.getString().contains("微波炉"), rebuilt.getString());
	}

	@Test
	void lockedContainerStringArgumentIsAlsoATranslationCandidate() {
		Component locked = Component.translatable("container.isLocked", "microwave");
		assertEquals(List.of("microwave"), ComponentExtractor.uniqueTexts(locked));
		Component rebuilt = ComponentExtractor.rebuild(locked, Map.of("microwave", "微波炉"));
		assertTrue(rebuilt.getString().contains("微波炉"), rebuilt.getString());
	}

	@Test
	void structuredNbtAndJsonLeavesAreNotTranslationCandidates() {
		assertTrue(ComponentExtractor.uniqueTexts(Component.literal("{CustomName:\"microwave\",Items:[]}"))
				.isEmpty());
		assertTrue(ComponentExtractor.uniqueTexts(Component.literal("[I; 12, 64, -3]"))
				.isEmpty());
		assertEquals(List.of("water"), ComponentExtractor.uniqueTexts(Component.literal("{water}")));
	}

	@Test
	void nonLiteralDataComponentsAreNeverTranslationCandidates() {
		Component data = Component.empty()
				.append(Component.keybind("key.jump"))
				.append(Component.score("Steve", "points"))
				.append(Component.literal("Hardcoded warning"));
		assertEquals(List.of("Hardcoded warning"), ComponentExtractor.uniqueTexts(data));
	}

	@Test
	void rebuildingKeepsTheCommandAndReplacesChatText() {
		Component command = Component.translatable("advMode.setCommand.success", "tell xxx Hello");
		Component rebuiltCommand = ComponentExtractor.rebuild(command,
				Map.of("tell xxx Hello", "告诉 xxx 你好"));
		assertTrue(rebuiltCommand.getContents() instanceof TranslatableContents);
		assertTrue(rebuiltCommand.getString().contains("tell xxx Hello"),
				"the command argument stays untouched: " + rebuiltCommand.getString());
		assertFalse(rebuiltCommand.getString().contains("告诉"), rebuiltCommand.getString());

		Component announcement = Component.translatable("chat.type.announcement", "Steve", "Meet me at the gate");
		Component rebuiltChat = ComponentExtractor.rebuild(announcement,
				Map.of("Meet me at the gate", "在门口等我"));
		assertTrue(rebuiltChat.getString().contains("在门口等我"), rebuiltChat.getString());
	}

	@Test
	void vanillaItemNamesAreOnlyTranslatedWhenEnabled() {
		Assumptions.assumeTrue(VanillaText.loadBlocking(10_000L),
				"vanilla language file not available in this environment");
		String english = VanillaText.englishFor("item.minecraft.diamond_sword");
		assertEquals("Diamond Sword", english);

		Component name = Component.translatable("item.minecraft.diamond_sword");
		assertTrue(ComponentExtractor.uniqueTexts(name).isEmpty(),
				"by default vanilla names are left to the game");

		ComponentExtractor.setTranslateVanillaNames(true);
		assertEquals(List.of("Diamond Sword"), ComponentExtractor.uniqueTexts(name));
	}

	@Test
	void vanillaKeysWithPlaceholdersOrArgumentsAreNeverRewritten() {
		Assumptions.assumeTrue(VanillaText.loadBlocking(10_000L),
				"vanilla language file not available in this environment");
		ComponentExtractor.setTranslateVanillaNames(true);

		// Contains %s: replacing it with a literal would drop the interpolation.
		Component withPlaceholder = Component.translatable("chat.type.text", "Steve", "hello");
		assertTrue(ComponentExtractor.uniqueTexts(withPlaceholder).stream()
				.noneMatch(text -> text.contains("%s")), "placeholder text must not be requested");

		// Command keys keep their vanilla wording even with the switch on.
		Component command = Component.translatable("advMode.setCommand.success", "tell xxx Hello");
		assertFalse(ComponentExtractor.uniqueTexts(command).contains("Command set: %s"));
	}
}
