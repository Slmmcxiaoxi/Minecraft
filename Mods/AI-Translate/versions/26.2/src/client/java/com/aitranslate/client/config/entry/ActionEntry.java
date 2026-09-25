package com.aitranslate.client.config.entry;

import java.util.List;
import java.util.Optional;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import com.aitranslate.client.config.ConfigUiMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A config row that carries a real button (open cache folder, test connection,
 * export cache, ...).
 * <p>
 * Buttons live inside the entry list instead of being pinned to the bottom of
 * the config screen, which is what made the previous layout overlap the
 * screen's own Save/Cancel/Back buttons.
 */
public class ActionEntry extends AbstractConfigListEntry<Boolean> {
	private final Button button;
	private final java.util.function.BooleanSupplier enabled;

	public ActionEntry(Component fieldName, Component buttonLabel, Runnable action, Component tooltip) {
		this(fieldName, buttonLabel, action, tooltip, () -> true);
	}

	public ActionEntry(Component fieldName, Component buttonLabel, Runnable action, Component tooltip,
			java.util.function.BooleanSupplier enabled) {
		super(fieldName, false);
		this.enabled = enabled == null ? () -> true : enabled;
		this.button = Button.builder(buttonLabel, pressed -> action.run()).bounds(0, 0,
				ConfigUiMetrics.STANDARD_BUTTON_WIDTH, ConfigUiMetrics.BUTTON_HEIGHT).build();
		if (tooltip != null) {
			button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tooltip));
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
		return 24;
	}

	/**
	 * Cloth's parameter order is {@code (index, y, x, entryWidth, entryHeight,
	 * mouseX, mouseY, isSelected, delta)}.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean isSelected, float delta) {
		Font font = Minecraft.getInstance().font;
		button.active = enabled.getAsBoolean();
		extractor.text(font, getFieldName(), x + 4, y + (entryHeight - 8) / 2, getPreferredTextColor());

		int buttonWidth = Math.min(ConfigUiMetrics.STANDARD_BUTTON_WIDTH, Math.max(72, entryWidth / 2));
		button.setWidth(buttonWidth);
		button.setX(x + entryWidth - buttonWidth - 4);
		button.setY(y + (entryHeight - button.getHeight()) / 2);
		button.extractRenderState(extractor, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		return button.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return button.mouseReleased(event);
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return List.of(button);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.of(button);
	}
}
