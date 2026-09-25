package com.aitranslate.client.capture;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Which kind of chat message a rendered chat line came from
 * (thirteenth feedback round).
 * <p>
 * The chat is one text source as far as the render layer is concerned - every line
 * arrives as a {@code GuiMessage.Line} - but a player thinks in commands, and the
 * thirteenth feedback round asks for a switch per command. The information is not
 * lost: the content of the message keeps the vanilla translation key it was built
 * with ({@code chat.type.text}, {@code chat.type.emote}, …), so a line can be
 * classified from the message it belongs to. That is what this class does, which is
 * why the per-command switches in the config screen are real switches and not
 * decoration.
 */
public enum ChatKind {
	/** Ordinary player chat: {@code <Steve> hello} ({@code chat.type.text}). */
	CHAT("聊天消息", "玩家聊天消息"),
	/** {@code /me} ({@code chat.type.emote}). */
	EMOTE("/me", "广播一条关于你的信息"),
	/** {@code /say} and other announcements ({@code chat.type.announcement}). */
	ANNOUNCEMENT("/say", "通过聊天框向多个玩家发送消息"),
	/** {@code /msg}, {@code /tell}, {@code /w} ({@code commands.message.display.*}). */
	PRIVATE("/msg /tell", "将一条私聊消息发送给一个或多个玩家"),
	/**
	 * {@code /tellraw} and any other hardcoded chat text - a map script, a reward
	 * message, another mod - i.e. a message that is <em>not</em> wrapped in a vanilla
	 * chat key.
	 */
	SCRIPT("/tellraw", "向一个或多个玩家发送一条以文本组件表示的消息"),
	/**
	 * Command feedback and command block output ({@code commands.*}, {@code advMode.*}).
	 * <p>
	 * Seventeenth feedback round: the switch for this kind was removed from the screen and
	 * the kind defaults to "not translated" - command feedback is the game talking to the
	 * player about commands, not map text.
	 */
	COMMAND_FEEDBACK("命令反馈", "命令反馈与命令方块输出（默认不翻译）");

	private final String label;
	private final String tooltip;

	ChatKind(String label, String tooltip) {
		this.label = label;
		this.tooltip = tooltip;
	}

	/** The name shown in the config screen. */
	public String label() {
		return label;
	}

	/**
	 * The explanation shown as a tooltip, so the row itself can stay short (the
	 * seventeenth feedback round asks for the command as the name and the description on
	 * hover).
	 */
	public String tooltip() {
		return tooltip;
	}

	/**
	 * The kind of a chat message, decided by the vanilla key its content was built
	 * with. Anything else - a literal, a styled component, a {@code /tellraw} payload
	 * - is {@link #SCRIPT}.
	 */
	public static ChatKind classify(Component message) {
		if (message == null) {
			return SCRIPT;
		}
		ComponentContents contents = message.getContents();
		if (!(contents instanceof TranslatableContents translatable)) {
			return SCRIPT;
		}
		return ofKey(translatable.getKey());
	}

	/** The kind behind a translation key (package private for tests). */
	static ChatKind ofKey(String key) {
		if (key == null || key.isEmpty()) {
			return SCRIPT;
		}
		if (key.startsWith("chat.type.emote")) {
			return EMOTE;
		}
		if (key.startsWith("chat.type.announcement")) {
			return ANNOUNCEMENT;
		}
		if (key.startsWith("commands.message.display.")) {
			return PRIVATE;
		}
		if (key.startsWith("multiplayer.player.joined") || key.startsWith("multiplayer.player.left")) {
			// Join/leave lines carry the same decorated player component as ordinary
			// chat (team prefix + profile name + team suffix). Treating them as SCRIPT
			// tied their decorations to the /tellraw switch, so the exact same team
			// name translated in chat and the scoreboard but stayed English here.
			return CHAT;
		}
		if (key.startsWith("death.")) {
			// Death messages carry the same decorated player component as chat. They
			// belong to the player-chat switch, not the /tellraw switch.
			return CHAT;
		}
		if (key.startsWith("commands.") || key.startsWith("advMode.")) {
			return COMMAND_FEEDBACK;
		}
		if (key.startsWith("chat.")) {
			// chat.type.text, chat.type.team.text, chat.type.team.sent, …: player chat in
			// all its flavours.
			return CHAT;
		}
		// Not a chat key: a system message with hardcoded content.
		return SCRIPT;
	}
}
