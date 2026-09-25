package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.keybinding.KeyCaptureManager;
import com.aitranslate.client.keybinding.SingleKeyManager;
import com.aitranslate.client.display.ScreenTextInputs;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/**
 * Key tracking for the mod.
 * <p>
 * Feeds every key press/release into {@link SingleKeyManager}'s held-key set and
 * forwards presses to {@link KeyCaptureManager} during rebinding.
 * <p>
 * The rebinding capture cancels the event so the press does not reach the game (the
 * config screen stays open, ESC does not close it, and the key is not forwarded to
 * any widget). Only the key-down event is consumed - releases pass through untouched.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

	@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
	private void aiTranslate$captureKeys(long window, int action, KeyEvent event, CallbackInfo info) {
		int key = event.key();
		if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
			SingleKeyManager.onKeyState(key, true);
		} else if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
			SingleKeyManager.onKeyState(key, false);
		}
		if (!KeyCaptureManager.isCapturing() || action != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		// Text input owns the complete keyboard/IME event stream. A stale key-binding
		// listener must never consume Ctrl/Shift/Space or a composition keystroke.
		if (ScreenTextInputs.isTextInputFocused(client.screen)) {
			KeyCaptureManager.cancel();
			return;
		}
		if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN || isModifier(key)) {
			return;
		}
		if (KeyCaptureManager.handleKey(key)) {
			info.cancel();
		}
	}

	private static boolean isModifier(int key) {
		return key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER;
	}
}
