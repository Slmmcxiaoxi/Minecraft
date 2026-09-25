package com.aitranslate.client.display;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;

/**
 * The button of the in-screen translation switch: a normal looking button that is
 * invisible to the keyboard focus chain (sixteenth feedback round).
 * <p>
 * <strong>The reported bug:</strong> "in the chat screen the keypad up/down keys are
 * captured by the translation button, so the chat history can no longer be browsed".
 * The cause is in {@code Screen#keyPressed} of 26.1: it turns the arrow keys into a
 * <em>focus navigation</em> event
 *
 * <pre>
 * key 265 (UP)  → createArrowEvent(UP)   → nextFocusPath(event) → changeFocus(...) → return true
 * key 264 (DOWN)→ createArrowEvent(DOWN) → same
 * </pre>
 *
 * and {@code ChatScreen#keyPressed} only reaches its own history handling
 * ({@code moveInHistory}) when {@code super.keyPressed(event)} returned {@code false}.
 * Vanilla has exactly one focusable widget on that screen (the chat input), so
 * {@code nextFocusPath(UP)} finds nothing and the key falls through - which is why the
 * history works in vanilla. Adding a second focusable widget (this mod's button) gave the
 * arrow keys somewhere to go: the key press was swallowed by the focus move, and browsing
 * the history with up/down stopped working. The same applies to the sign editor, the book
 * and quill editor and dialog inputs, which is why this is fixed in the button itself
 * instead of in one screen.
 * <p>
 * The fix is what the feedback document asks for - the switch is a <em>mouse</em> control
 * and must not participate in the keyboard focus chain:
 *
 * <ul>
 * <li>{@link #nextFocusPath(FocusNavigationEvent)} returns {@code null}, so arrow keys and
 * Tab can never move the focus onto it (and the key press stays free for the screen's own
 * handling);</li>
 * <li>{@link #shouldTakeFocusAfterInteraction()} returns {@code false}, so clicking the
 * button does not take the focus away from the chat input either - that was the second
 * half of the problem: after a click the player could not continue typing in the field
 * they were in;</li>
 * <li>{@code setFocused} is ignored and {@code isFocused} is always {@code false}, so
 * nothing else can put it into the focus chain.</li>
 * </ul>
 *
 * The button is still fully usable with the mouse (that is the point of it), and the key
 * binding of the in-screen switch keeps working through {@code ScreenKeyMixin}, which
 * stands down while a text input has the focus.
 */
public class UnfocusableButton extends Button.Plain {

	public UnfocusableButton(int x, int y, int width, int height, Component message, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
	}

	/** Never a focus target: arrow keys and Tab must not land here. */
	@Override
	public ComponentPath nextFocusPath(FocusNavigationEvent event) {
		return null;
	}

	/** A click must not take the focus away from whatever the player is typing in. */
	@Override
	public boolean shouldTakeFocusAfterInteraction() {
		return false;
	}

	@Override
	public void setFocused(boolean focused) {
		// Ignored on purpose: see the class comment.
	}

	@Override
	public boolean isFocused() {
		return false;
	}
}
