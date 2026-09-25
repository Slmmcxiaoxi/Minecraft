package com.aitranslate.client.keybinding;

import org.lwjgl.glfw.GLFW;

/** One rebindable GLFW key. {@link #UNBOUND} disables the binding. */
public record SingleKeyBinding(int keyCode) {
	public static final int UNBOUND = GLFW.GLFW_KEY_UNKNOWN;

	public static SingleKeyBinding unbound() {
		return new SingleKeyBinding(UNBOUND);
	}

	public boolean isBound() {
		return keyCode != UNBOUND;
	}

	/** True while this one key is held, using events plus GLFW as a fallback. */
	public boolean isPressed(long windowHandle, java.util.Set<Integer> held) {
		if (!isBound()) {
			return false;
		}
		return held != null && held.contains(keyCode)
				|| windowHandle != 0L && GLFW.glfwGetKey(windowHandle, keyCode) == GLFW.GLFW_PRESS;
	}

	public String describe() {
		return isBound() ? keyName(keyCode) : "未绑定";
	}

	/** Human-readable name without calling GLFW before window initialization. */
	public static String keyName(int keyCode) {
		if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
			return String.valueOf((char) ('A' + keyCode - GLFW.GLFW_KEY_A));
		}
		if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
			return String.valueOf((char) ('0' + keyCode - GLFW.GLFW_KEY_0));
		}
		if (keyCode >= GLFW.GLFW_KEY_F1 && keyCode <= GLFW.GLFW_KEY_F25) {
			return "F" + (keyCode - GLFW.GLFW_KEY_F1 + 1);
		}
		if (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9) {
			return "NUM" + (keyCode - GLFW.GLFW_KEY_KP_0);
		}
		return switch (keyCode) {
			case GLFW.GLFW_KEY_SPACE -> "SPACE";
			case GLFW.GLFW_KEY_APOSTROPHE -> "'";
			case GLFW.GLFW_KEY_COMMA -> ",";
			case GLFW.GLFW_KEY_MINUS -> "-";
			case GLFW.GLFW_KEY_PERIOD -> ".";
			case GLFW.GLFW_KEY_SLASH -> "/";
			case GLFW.GLFW_KEY_SEMICOLON -> ";";
			case GLFW.GLFW_KEY_EQUAL -> "=";
			case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
			case GLFW.GLFW_KEY_BACKSLASH -> "\\";
			case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
			case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
			case GLFW.GLFW_KEY_ESCAPE -> "ESC";
			case GLFW.GLFW_KEY_ENTER -> "ENTER";
			case GLFW.GLFW_KEY_TAB -> "TAB";
			case GLFW.GLFW_KEY_BACKSPACE -> "BACKSPACE";
			case GLFW.GLFW_KEY_INSERT -> "INSERT";
			case GLFW.GLFW_KEY_DELETE -> "DELETE";
			case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
			case GLFW.GLFW_KEY_LEFT -> "LEFT";
			case GLFW.GLFW_KEY_DOWN -> "DOWN";
			case GLFW.GLFW_KEY_UP -> "UP";
			case GLFW.GLFW_KEY_PAGE_UP -> "PAGE_UP";
			case GLFW.GLFW_KEY_PAGE_DOWN -> "PAGE_DOWN";
			case GLFW.GLFW_KEY_HOME -> "HOME";
			case GLFW.GLFW_KEY_END -> "END";
			case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS_LOCK";
			case GLFW.GLFW_KEY_SCROLL_LOCK -> "SCROLL_LOCK";
			case GLFW.GLFW_KEY_NUM_LOCK -> "NUM_LOCK";
			case GLFW.GLFW_KEY_PRINT_SCREEN -> "PRINT_SCREEN";
			case GLFW.GLFW_KEY_PAUSE -> "PAUSE";
			case GLFW.GLFW_KEY_KP_DECIMAL -> "NUM.";
			case GLFW.GLFW_KEY_KP_DIVIDE -> "NUM/";
			case GLFW.GLFW_KEY_KP_MULTIPLY -> "NUM*";
			case GLFW.GLFW_KEY_KP_SUBTRACT -> "NUM-";
			case GLFW.GLFW_KEY_KP_ADD -> "NUM+";
			case GLFW.GLFW_KEY_KP_ENTER -> "NUM_ENTER";
			case GLFW.GLFW_KEY_KP_EQUAL -> "NUM=";
			case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
			case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
			case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
			case GLFW.GLFW_KEY_LEFT_SUPER -> "LWIN";
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
			case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
			case GLFW.GLFW_KEY_RIGHT_SUPER -> "RWIN";
			case GLFW.GLFW_KEY_MENU -> "MENU";
			case GLFW.GLFW_KEY_UNKNOWN -> "未绑定";
			default -> "KEY_" + keyCode;
		};
	}
}
