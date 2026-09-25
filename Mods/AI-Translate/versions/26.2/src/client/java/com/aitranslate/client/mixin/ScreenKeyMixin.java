package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.display.ScreenOriginalMode;
import com.aitranslate.client.display.ScreenTextInputs;
import com.aitranslate.client.display.ScreenToggleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;

/**
 * The in-screen switch between the translation and the original text
 * (thirteenth feedback round).
 * <p>
 * The master switch key cannot reach the player while a screen is open: the key
 * bindings are deliberately suspended then (a screen must not trigger them while the
 * player types). This hook is the exception, and it is placed at the <em>end</em> of
 * {@code Screen#keyPressed} so that it only acts when the screen and its focused
 * widget did <em>not</em> want the key:
 *
 * <ul>
 * <li>a text field that consumed the press (typing "R" in an anvil or chat box)
 * returns {@code true}, so the switch stays quiet;</li>
 * <li>any other key press in a supported screen is unused, and the mod answers it.</li>
 * </ul>
 *
 * That single rule is why the switch can be a plain letter without stealing input
 * from the game, and why it works in every supported screen without touching any of
 * their key handling.
 * <p>
 * Fifteenth feedback round, second rule: the "did the screen want the key" test is
 * not enough on its own. A screen whose input box has focus but does not consume a
 * particular key (the chat box ignores function keys, a mod's editor may ignore
 * anything it does not map, and {@code Ctrl + A} style combinations are frequently
 * passed on) would let the switch fire in the middle of typing - reported as "typing
 * {@code redstone} toggles the translation". While an input box has focus the switch
 * now stands down completely, whatever the screen's answer was. The button is not
 * affected by this: it is a mouse control and keeps working exactly when the key
 * cannot be used.
 * <p>
 * A book-and-quill is always an active editor, so its shortcut is disabled for the
 * lifetime of that screen. This also avoids consuming intermediate IME key events;
 * its on-screen mouse button remains available.
 */
@Mixin(Screen.class)
public abstract class ScreenKeyMixin {
	@Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("RETURN"), cancellable = true)
	private void aiTranslate$toggleOriginal(KeyEvent event, CallbackInfoReturnable<Boolean> info) {
		if (info.getReturnValueZ()) {
			// The screen or the focused widget used this key.
			return;
		}
		Screen screen = (Screen) (Object) this;
		if (!ScreenOriginalMode.supports(screen)) {
			return;
		}
		if (screen instanceof net.minecraft.client.gui.screens.inventory.BookEditScreen) {
			// A book-and-quill is an editor for its whole lifetime. Its page widget
			// consumes ordinary letters before Screen#keyPressed returns, and IME
			// composition can deliver intermediate key events which are not consumed.
			// Never interpret a key as the display shortcut here; the screen button is
			// the deliberate way to switch while editing.
			return;
		}
		if (screen.getClass().getName().endsWith("SignEditScreen")) {
			// Sign editors keep their text state directly instead of exposing an EditBox.
			// Treat the whole screen as an active editor so physical keys and IME
			// composition are always delivered unchanged.
			return;
		}
		if (ScreenTextInputs.isTextInputFocused(screen)) {
			// Covers the anvil naming EditBox (and chat): while the player is typing,
			// including through an IME, the shortcut keeps its hands off. The mouse
			// button still works.
			return;
		}
		if (!ScreenToggleButton.matchesKey(event.key(), event.modifiers())) {
			return;
		}
		ScreenOriginalMode.toggle(screen);
		info.setReturnValue(true);
	}
}
