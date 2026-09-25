package com.aitranslate.client.keybinding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Single global key listener used by the in-place key rebinding entry.
 * <p>
 * While a listener is active, {@code KeyboardHandlerMixin} feeds the next key press
 * to it and cancels the event, so the game does not react. ESC cancels capture;
 * Delete/Backspace can be interpreted by the entry as "unbound".
 */
public final class KeyCaptureManager {

	public interface Listener {
		/** @return {@code true} when the key was consumed. */
		boolean onKeyPressed(int keyCode);

		void onCancelled();
	}

	private static volatile Listener listener;
	/** Screen that started the capture; a capture must never leak into another screen. */
	private static volatile Screen owner;

	private KeyCaptureManager() {
	}

	public static boolean isCapturing() {
		if (listener == null) {
			return false;
		}
		if (owner == null || Minecraft.getInstance().gui.screen() != owner) {
			cancel();
			return false;
		}
		return true;
	}

	public static void begin(Listener newListener) {
		listener = newListener;
		owner = Minecraft.getInstance().gui.screen();
	}

	public static void cancel() {
		Listener current = listener;
		listener = null;
		owner = null;
		if (current != null) {
			current.onCancelled();
		}
	}

	/** Drops the listener without notifying it (used after a successful commit). */
	public static void stop() {
		listener = null;
		owner = null;
	}

	/** Called from the keyboard mixin; returns true when the key was consumed. */
	public static boolean handleKey(int keyCode) {
		Listener current = listener;
		if (current == null) {
			return false;
		}
		return current.onKeyPressed(keyCode);
	}

}
