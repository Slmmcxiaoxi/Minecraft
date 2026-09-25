package com.aitranslate.client.util;

import com.aitranslate.AITranslateMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client side feedback helper.
 * <p>
 * Messages are added to the local chat component, so no packet is ever sent to
 * the server (26.1 replaced {@code LocalPlayer#displayClientMessage} with
 * {@code ChatComponent#addClientSystemMessage}).
 * <p>
 * Sixth feedback round: every output of the mod - chat, action bar, command
 * feedback and the log - uses the short prefix {@code [AT]}.
 */
public final class ChatFeedback {
	/** The single output prefix of the mod. */
	public static final String PREFIX = "[AT]";

	private ChatFeedback() {
	}

	public static void send(String text) {
		send(Component.literal(text));
	}

	public static void send(Component component) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.gui == null || client.gui.hud.getChat() == null) {
			AITranslateMod.LOGGER.info("{} {}", PREFIX, component.getString());
			return;
		}
		client.gui.hud.getChat().addClientSystemMessage(
				Component.literal(PREFIX + " ").append(component));
	}

	/** Action bar style notification (used by the master switch). */
	public static void overlay(Component component) {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.gui != null) {
			client.gui.hud.setOverlayMessage(component, false);
		}
	}
}
