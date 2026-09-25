package com.aitranslate.client.config.entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import com.aitranslate.client.config.ConfigUiMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A config row that carries several small buttons on its right hand side
 * (thirteenth feedback round: one row per world in the cache list, with
 * 导出 / 导入 / 清理 next to it).
 * <p>
 * The buttons live inside the entry list rather than being pinned to the bottom of
 * the screen, so they can never cover the screen's own Save/Cancel/Back buttons.
 */
public class MultiActionEntry extends AbstractConfigListEntry<Boolean> {

	/** One button: its label, what it does and what it means. */
	public record Action(Component label, Runnable action, Component tooltip,
			java.util.function.BooleanSupplier enabled) {
		public Action(Component label, Runnable action, Component tooltip) {
			this(label, action, tooltip, () -> true);
		}

		public Action {
			enabled = enabled == null ? () -> true : enabled;
		}
	}

	private final List<Button> buttons = new ArrayList<>();
	private final List<Action> actions;
	private final Component hint;
	private final int rowHeight;

	public MultiActionEntry(Component fieldName, List<Action> actions, Component hint) {
		this(fieldName, actions, hint, 24);
	}

	public MultiActionEntry(Component fieldName, List<Action> actions, Component hint, int rowHeight) {
		super(fieldName, false);
		this.actions = List.copyOf(actions);
		this.hint = hint;
		this.rowHeight = rowHeight;
		for (Action action : this.actions) {
			Button button = Button.builder(action.label(), pressed -> action.action().run())
					.bounds(0, 0, ConfigUiMetrics.SMALL_BUTTON_WIDTH, ConfigUiMetrics.BUTTON_HEIGHT)
					.build();
			if (action.tooltip() != null) {
				button.setTooltip(Tooltip.create(action.tooltip()));
			}
			buttons.add(button);
		}
	}

	@Override
	public Boolean getValue() {
		return Boolean.FALSE;
	}

	@Override
	public Optional<Boolean> getDefaultValue() {
		return Optional.empty();
	}

	@Override
	public int getItemHeight() {
		return rowHeight;
	}

	/**
	 * Cloth's parameter order is {@code (index, y, x, entryWidth, entryHeight,
	 * mouseX, mouseY, isSelected, delta)}.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean isSelected, float delta) {
		Font font = Minecraft.getInstance().font;
		extractor.text(font, getFieldName(), x + 4, y + 6, getPreferredTextColor());
		if (hint != null) {
			extractor.text(font, hint, x + 4, y + 17, 0xFF909090);
		}
		int buttonWidth = ConfigUiMetrics.SMALL_BUTTON_WIDTH;
		int gap = ConfigUiMetrics.GAP;
		int totalWidth = buttons.size() * buttonWidth + Math.max(0, buttons.size() - 1) * gap;
		int startX = x + entryWidth - totalWidth - 4;
		for (int i = 0; i < buttons.size(); i++) {
			Button button = buttons.get(i);
			button.active = actions.get(i).enabled().getAsBoolean();
			button.setWidth(buttonWidth);
			button.setX(startX + i * (buttonWidth + gap));
			button.setY(y + (entryHeight - button.getHeight()) / 2);
			button.extractRenderState(extractor, mouseX, mouseY, delta);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		for (Button button : buttons) {
			if (button.mouseClicked(event, doubled)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = false;
		for (Button button : buttons) {
			handled |= button.mouseReleased(event);
		}
		return handled;
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return List.copyOf(buttons);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.copyOf(buttons);
	}
}
