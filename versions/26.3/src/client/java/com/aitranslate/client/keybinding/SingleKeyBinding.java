package com.aitranslate.client.keybinding;

import com.mojang.blaze3d.platform.InputConstants;

/** One rebindable Minecraft keyboard key. {@link #UNBOUND} disables the binding. */
public record SingleKeyBinding(int keyCode) {
	public static final int UNBOUND = -1;

	public static SingleKeyBinding unbound() {
		return new SingleKeyBinding(UNBOUND);
	}

	public boolean isBound() {
		return keyCode != UNBOUND;
	}

	/** True while this one key is held, using events plus Minecraft's input layer. */
	public boolean isPressed(long windowHandle, java.util.Set<Integer> held) {
		if (!isBound()) {
			return false;
		}
		return held != null && held.contains(keyCode) || InputConstants.isKeyDown(keyCode);
	}

	public String describe() {
		return isBound() ? keyName(keyCode) : "未绑定";
	}

	/** Human-readable name supplied by Minecraft's platform-independent key table. */
	public static String keyName(int keyCode) {
		if (keyCode == UNBOUND) return "未绑定";
		return InputConstants.Type.KEYBOARD.getOrCreate(keyCode).getDisplayName().getString();
	}
}
