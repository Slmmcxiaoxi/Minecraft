package com.aitranslate.client.display;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * Text input widgets of a screen (fifteenth feedback round).
 * <p>
 * Two questions are answered here, and both of them need the <em>whole</em> widget
 * tree rather than the screen's direct children:
 *
 * <ul>
 * <li>{@link #hasTextInput(Screen)}: does this screen contain an input box at all?
 * That is what decides whether the in-screen switch is offered (a screen with an
 * input box is exactly the screen where the master switch key cannot reach the
 * player).</li>
 * <li>{@link #isTextInputFocused(Screen)}: is the player typing right now? The
 * short-answer version of the feedback report was "typing {@code redstone} in the
 * chat must not toggle the translation", so the switch has to stand down while an
 * input box has focus - even when the pressed key is one the box itself does not
 * consume.</li>
 * </ul>
 *
 * The search walks {@code children()} recursively with a depth limit and a visited
 * set: a container that returns itself (or two containers that reference each
 * other) would otherwise loop forever, and this runs from a key press, i.e. from
 * the render thread.
 */
public final class ScreenTextInputs {

	/** Depth limit for the recursive widget walk (screens are 2-3 levels deep). */
	private static final int MAX_DEPTH = 6;

	private ScreenTextInputs() {
	}

	/** True when the screen contains a single line or multi line text input. */
	public static boolean hasTextInput(Screen screen) {
		return contains(screen == null ? null : screen.children(), true);
	}

	/** True when one of the screen's input widgets currently has focus. */
	public static boolean isTextInputFocused(Screen screen) {
		return contains(screen == null ? null : screen.children(), false);
	}

	private static boolean contains(java.util.List<? extends GuiEventListener> children, boolean anyInput) {
		return walk(children, anyInput, 0, new HashSet<>());
	}

	private static boolean walk(java.util.List<? extends GuiEventListener> children, boolean anyInput, int depth,
			Set<GuiEventListener> visited) {
		if (children == null || depth > MAX_DEPTH) {
			return false;
		}
		for (GuiEventListener child : children) {
			if (child == null || !visited.add(child)) {
				continue;
			}
			if (child instanceof EditBox || child instanceof MultiLineEditBox) {
				if (anyInput || child.isFocused()) {
					return true;
				}
				// An unfocused input is not what the focus question asks about, but the
				// same widget can still hold a focused child (it cannot), so the walk
				// simply goes on.
				continue;
			}
			if (walk(childrenOf(child), anyInput, depth + 1, visited)) {
				return true;
			}
		}
		return false;
	}

	/** The children of a widget, or {@code null} when it is a leaf. */
	private static java.util.List<? extends GuiEventListener> childrenOf(GuiEventListener widget) {
		return widget instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container
				? container.children()
				: null;
	}
}
