package com.aitranslate.client.keybinding;

import com.mojang.blaze3d.platform.InputConstants;

/** Converts the GLFW key integers saved by 26.2 and older to 26.3 scan codes. */
public final class LegacyKeyCodes {
	private LegacyKeyCodes() {}

	public static int toMinecraftKey(int glfw) {
		if (glfw == -1) return SingleKeyBinding.UNBOUND;
		if (glfw >= 65 && glfw <= 90) return InputConstants.KEY_A + glfw - 65;
		if (glfw >= 48 && glfw <= 57) return glfw == 48 ? InputConstants.KEY_0 : InputConstants.KEY_1 + glfw - 49;
		if (glfw >= 290 && glfw <= 301) return InputConstants.KEY_F1 + glfw - 290;
		if (glfw >= 302 && glfw <= 313) return 104 + glfw - 302;
		if (glfw >= 320 && glfw <= 329) return glfw == 320 ? InputConstants.KEY_NUMPAD0 : InputConstants.KEY_NUMPAD1 + glfw - 321;
		return switch (glfw) {
			case 32 -> InputConstants.KEY_SPACE;
			case 39 -> InputConstants.KEY_APOSTROPHE;
			case 44 -> InputConstants.KEY_COMMA;
			case 45 -> InputConstants.KEY_MINUS;
			case 46 -> InputConstants.KEY_PERIOD;
			case 47 -> InputConstants.KEY_SLASH;
			case 59 -> InputConstants.KEY_SEMICOLON;
			case 61 -> InputConstants.KEY_EQUALS;
			case 91 -> InputConstants.KEY_LBRACKET;
			case 92 -> InputConstants.KEY_BACKSLASH;
			case 93 -> InputConstants.KEY_RBRACKET;
			case 96 -> InputConstants.KEY_GRAVE;
			case 256 -> InputConstants.KEY_ESCAPE;
			case 257 -> InputConstants.KEY_RETURN;
			case 258 -> InputConstants.KEY_TAB;
			case 259 -> InputConstants.KEY_BACKSPACE;
			case 260 -> InputConstants.KEY_INSERT;
			case 261 -> InputConstants.KEY_DELETE;
			case 262 -> InputConstants.KEY_RIGHT;
			case 263 -> InputConstants.KEY_LEFT;
			case 264 -> InputConstants.KEY_DOWN;
			case 265 -> InputConstants.KEY_UP;
			case 266 -> InputConstants.KEY_PAGEUP;
			case 267 -> InputConstants.KEY_PAGEDOWN;
			case 268 -> InputConstants.KEY_HOME;
			case 269 -> InputConstants.KEY_END;
			case 280 -> InputConstants.KEY_CAPSLOCK;
			case 281 -> InputConstants.KEY_SCROLLLOCK;
			case 282 -> InputConstants.KEY_NUMLOCK;
			case 283 -> InputConstants.KEY_PRINTSCREEN;
			case 284 -> InputConstants.KEY_PAUSE;
			case 330 -> 99; // keypad period
			case 331 -> 84; // keypad divide
			case 332 -> InputConstants.KEY_MULTIPLY;
			case 333 -> 86; // keypad subtract
			case 334 -> InputConstants.KEY_ADD;
			case 335 -> InputConstants.KEY_NUMPADENTER;
			case 336 -> InputConstants.KEY_NUMPADEQUALS;
			case 340 -> InputConstants.KEY_LSHIFT;
			case 341 -> InputConstants.KEY_LCONTROL;
			case 342 -> InputConstants.KEY_LALT;
			case 343 -> InputConstants.KEY_LGUI;
			case 344 -> InputConstants.KEY_RSHIFT;
			case 345 -> InputConstants.KEY_RCONTROL;
			case 346 -> InputConstants.KEY_RALT;
			case 347 -> InputConstants.KEY_RGUI;
			case 348 -> 101; // application/menu
			default -> SingleKeyBinding.UNBOUND;
		};
	}
}
