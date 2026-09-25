package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The chat kind classifier (thirteenth feedback round).
 * <p>
 * The 翻译选项 page offers a switch per command, and a switch that does not really
 * switch anything would be worse than no switch at all. The kind of a rendered chat
 * line is read from the vanilla key its message was built with, so these cases are
 * exactly the ones a map produces.
 */
class ChatKindTest {

	@Test
	void playerChatIsChat() {
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("chat.type.text"));
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("chat.type.team.text"));
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("chat.type.team.sent"));
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("chat.type.team.text"),
				"/teammsg must share the ordinary player-chat switch");
	}

	@Test
	void theCommandsHaveTheirOwnKinds() {
		assertEquals(ChatKind.EMOTE, ChatKind.ofKey("chat.type.emote"));
		assertEquals(ChatKind.ANNOUNCEMENT, ChatKind.ofKey("chat.type.announcement"));
		assertEquals(ChatKind.PRIVATE, ChatKind.ofKey("commands.message.display.incoming"));
		assertEquals(ChatKind.PRIVATE, ChatKind.ofKey("commands.message.display.outgoing"));
	}

	@Test
	void commandFeedbackIsRecognised() {
		assertEquals(ChatKind.COMMAND_FEEDBACK, ChatKind.ofKey("commands.give.success.single"));
		assertEquals(ChatKind.COMMAND_FEEDBACK, ChatKind.ofKey("advMode.setCommand.success"));
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("death.attack.genericKill"));
	}

	@Test
	void joinAndLeaveDecorationsShareThePlayerChatSwitch() {
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("multiplayer.player.joined"));
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("multiplayer.player.joined.renamed"));
		assertEquals(ChatKind.CHAT, ChatKind.ofKey("multiplayer.player.left"));
	}

	@Test
	void hardcodedChatTextIsScript() {
		// /tellraw, a map's reward message, another mod: no chat key at all.
		assertEquals(ChatKind.SCRIPT,
				ChatKind.classify(net.minecraft.network.chat.Component.literal("You found the amulet!")));
		assertEquals(ChatKind.SCRIPT, ChatKind.ofKey("some.other.key"));
		assertEquals(ChatKind.SCRIPT, ChatKind.ofKey(null));
		assertEquals(ChatKind.SCRIPT, ChatKind.ofKey(""));
		assertEquals(ChatKind.SCRIPT, ChatKind.classify(null));
	}

	@Test
	void aRealTranslatableMessageIsClassified() {
		assertEquals(ChatKind.CHAT, ChatKind.classify(net.minecraft.network.chat.Component.translatable(
				"chat.type.text", net.minecraft.network.chat.Component.literal("Steve"),
				net.minecraft.network.chat.Component.literal("hello"))));
		assertEquals(ChatKind.EMOTE, ChatKind.classify(net.minecraft.network.chat.Component
				.translatable("chat.type.emote", net.minecraft.network.chat.Component.literal("Steve"),
						net.minecraft.network.chat.Component.literal("waves"))));
	}
}
