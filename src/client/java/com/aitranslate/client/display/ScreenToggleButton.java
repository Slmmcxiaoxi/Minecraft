package com.aitranslate.client.display;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.config.ModConfig;
import com.aitranslate.client.keybinding.SingleKeyBinding;
import com.aitranslate.client.mixin.ScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The in-screen "译 / 原" button (thirteenth feedback round, extended to every
 * in-game screen in the fourteenth).
 * <p>
 * Why a button at all: the key is captured by any text input, and the screens where
 * a player wants to compare the translation with the original - the chat box, the
 * book and quill, a sign editor - are exactly the screens with a text input. A
 * clickable control works there, the key does not.
 * <p>
 * Why a per-tick check instead of a mixin per screen: the button has to appear on
 * every screen that shows the mod's text, including screens other mods add. A hook
 * in {@code Screen#init} only reaches screens that call it (and misses the ones that
 * override {@code init} without calling {@code super}), so the button is placed from
 * the client tick instead - one map lookup per tick.
 * <p>
 * Fifteenth feedback round, three fixes in this class:
 * <ul>
 * <li><strong>The button vanished after one click.</strong> A screen that rebuilds
 * its widget list ({@code rebuildWidgets()}, which every resize and every Cloth
 * Config refresh does) throws the button away, and the old code only ever added the
 * button when its map had no entry for the screen - so it was never added back.
 * The tick now checks that the button is still one of the screen's children and
 * re-adds it when it is not.</li>
 * <li><strong>The button covered the chat input box.</strong> The chat screen had a
 * special case that parked it in the bottom right corner, right on top of the input
 * row. The special case is gone: one anchor based position for every screen, and the
 * player can move it where they like it (see {@link ButtonPlacement}).</li>
 * <li><strong>No way to turn it off or move it.</strong> Both are config values now
 * (全局 → 按键绑定++ → 界面内切换) and the button disappears without leaving a
 * half-removed widget behind when it is switched off.</li>
 * </ul>
 */
public final class ScreenToggleButton {

	/** Button size; also used by the drag screen's preview so both look identical. */
	public static final int WIDTH = 28;
	public static final int HEIGHT = 18;

	/**
	 * Screens that already have the button, so a rebuild is noticed and repaired.
	 * <p>
	 * The button is deliberately built without a reference to its screen (it looks the
	 * current screen up when it is clicked). A value that pointed back at its key would
	 * keep the entry alive in this weak map forever.
	 */
	private static final Map<Screen, Button> ATTACHED =
			Collections.synchronizedMap(new WeakHashMap<>());

	private ScreenToggleButton() {
	}

	/**
	 * Makes sure the current screen shows the button, and keeps its label, its
	 * position and its enabled state in sync. Called once per client tick.
	 */
	public static void tick(Minecraft client) {
		Screen screen = client == null ? null : client.screen;
		if (screen == null || !ScreenOriginalMode.supports(screen)) {
			return;
		}
		ModConfig config = AITranslateModClient.config;
		Button button = ATTACHED.get(screen);
		if (config != null && !config.showScreenButton) {
			// Switched off in the config: take the button back out of the screen it was
			// added to, so the change is visible immediately (the config screen is a
			// different screen, so this also covers "hide it while the config is open").
			remove(screen, button);
			return;
		}
		if (button == null) {
			button = create();
			ATTACHED.put(screen, button);
			((ScreenAccessor) screen).aiTranslate$addRenderableWidget(button);
		} else if (!isAttached(screen, button)) {
			// The screen rebuilt its widgets (resize, page switch, another mod's refresh)
			// and dropped our button; the button object is still ours, so it only needs to
			// be added again.
			((ScreenAccessor) screen).aiTranslate$addRenderableWidget(button);
		}
		update(button, screen);
	}

	private static Button create() {
		// No capture of the screen: the click looks up whatever screen is open, which is
		// also correct when the player clicks the button in a screen that shares it.
		//
		// An UnfocusableButton on purpose (sixteenth feedback round): arrow keys are focus
		// navigation in 26.1, so a focusable button next to the chat input swallowed the
		// up/down history keys - see that class for the bytecode-level reason.
		return new UnfocusableButton(0, 0, WIDTH, HEIGHT, Component.literal("译"), pressed -> {
			Screen current = Minecraft.getInstance().screen;
			if (current != null) {
				ScreenOriginalMode.toggle(current);
				update(pressed, current);
			}
		});
	}

	/** A button that looks exactly like the real one and does nothing (drag preview). */
	public static Button createPreview() {
		Button preview = new UnfocusableButton(0, 0, WIDTH, HEIGHT, Component.literal("译"), pressed -> {
		});
		preview.setTooltip(Tooltip.create(Component.literal("拖动这个按钮，松手后吸附到最近的角")));
		return preview;
	}

	private static void remove(Screen screen, Button button) {
		if (button == null) {
			return;
		}
		ATTACHED.remove(screen);
		((ScreenAccessor) screen).aiTranslate$removeWidget(button);
	}

	/** True while the button is one of the screen's widgets (i.e. it is still shown). */
	private static boolean isAttached(Screen screen, Button button) {
		try {
			return screen.children().contains(button);
		} catch (RuntimeException e) {
			// A screen whose children() throws cannot be reasoned about; assume attached
			// rather than adding the same widget twice.
			return true;
		}
	}

	/** Keeps the label ("译" / "原"), the tooltip and the enabled state current. */
	private static void update(Button button, Screen screen) {
		button.setMessage(label(screen));
		button.setTooltip(Tooltip.create(tooltip(screen)));
		var config = AITranslateModClient.config;
		boolean usable = config == null || config.translateEnabled;
		if (button.active != usable) {
			button.active = usable;
		}
		place(button, screen);
	}

	private static Component label(Screen screen) {
		// "原" while the originals are shown, "译" while the translations are shown: the
		// label says what is on screen, and the tooltip says what a click does.
		return Component.literal(ScreenOriginalMode.isShowingOriginal(screen) ? "原" : "译");
	}

	private static Component tooltip(Screen screen) {
		var config = AITranslateModClient.config;
		if (config != null && !config.translateEnabled) {
			return Component.literal("翻译总开关已关闭，没有译文可切换");
		}
		return Component.literal(ScreenOriginalMode.isShowingOriginal(screen)
				? "当前显示原文，点击切回译文"
				: "当前显示译文，点击查看原文")
				.append(Component.literal("（快捷键 " + originalBinding().describe() + "）"));
	}

	/**
	 * The one position every screen uses (fifteenth feedback round).
	 * <p>
	 * Measured from the window rather than from the screen, so the button lands in the
	 * same place on every screen type and never needs access to the screen's protected
	 * size fields. The anchor and the offsets come from the config, which is what makes
	 * the position adjustable and stable across window sizes.
	 */
	private static void place(Button button, Screen screen) {
		Minecraft client = Minecraft.getInstance();
		int width = client.getWindow() == null ? 320 : client.getWindow().getGuiScaledWidth();
		int height = client.getWindow() == null ? 240 : client.getWindow().getGuiScaledHeight();
		ModConfig config = AITranslateModClient.config;
		ButtonPlacement.Anchor anchor = config == null ? ButtonPlacement.Anchor.TOP_RIGHT
				: ButtonPlacement.Anchor.parseOr(config.buttonAnchor, ButtonPlacement.Anchor.TOP_RIGHT);
		int offsetX = config == null ? -10 : config.buttonOffsetX;
		int offsetY = config == null ? 10 : config.buttonOffsetY;
		ButtonPlacement.Position position =
				ButtonPlacement.place(anchor, offsetX, offsetY, width, height, WIDTH, HEIGHT);
		if (button.getX() != position.x() || button.getY() != position.y()) {
			button.setX(position.x());
			button.setY(position.y());
		}
	}

	private static SingleKeyBinding originalBinding() {
		if (AITranslateModClient.keybindings == null) {
			return SingleKeyBinding.unbound();
		}
		return AITranslateModClient.keybindings.originalBinding();
	}

	/** The key that triggers the in-screen switch, or {@code false} when unbound. */
	public static boolean matchesKey(int key, int glfwModifiers) {
		SingleKeyBinding binding = originalBinding();
		return binding != null && binding.isBound() && binding.keyCode() == key;
	}

}
