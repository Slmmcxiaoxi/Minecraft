package com.aitranslate.client.config.entry;

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
 * The API connection state as a coloured dot, with the test button next to it
 * (thirteenth feedback round).
 * <p>
 * The feedback document asks for the state to be readable at a glance and explicitly
 * <em>not</em> as a chat message - chat is covered by the config screen anyway, so a
 * chat line would be invisible exactly when it is needed. The dot is a real glyph
 * ({@code ●}) drawn in red, green or grey, which is why it scales with the player's
 * GUI scale like every other character.
 */
public class StatusDotEntry extends AbstractConfigListEntry<Boolean> {

	/** What the dot shows. */
	public enum State {
		/** Nothing tested yet. */
		UNKNOWN(0xFFAAAAAA, "尚未测试"),
		/** A request is currently in flight. */
		TESTING(0xFFFFC857, "检测中"),
		/** The last test answered. */
		OK(0xFF43D17C, "连接成功"),
		/** The last test failed. */
		FAILED(0xFFE04B4B, "连接失败");

		private final int color;
		private final String label;

		State(int color, String label) {
			this.color = color;
			this.label = label;
		}

		public int color() {
			return color;
		}

		public String label() {
			return label;
		}
	}

	private final java.util.function.Supplier<State> state;
	private final Button button;

	public StatusDotEntry(Component fieldName, java.util.function.Supplier<State> state, Component buttonLabel,
			Runnable action) {
		super(fieldName, false);
		this.state = state;
		this.button = Button.builder(buttonLabel, pressed -> action.run()).bounds(0, 0,
				ConfigUiMetrics.STANDARD_BUTTON_WIDTH, ConfigUiMetrics.BUTTON_HEIGHT).build();
		this.button.setTooltip(Tooltip.create(Component.literal("发送一条极短的测试请求，结果只显示在右侧圆点上")));
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

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean isSelected, float delta) {
		Font font = Minecraft.getInstance().font;
		State current = state.get();
		button.active = current != State.TESTING;
		extractor.text(font, getFieldName(), x + 4, y + (entryHeight - 8) / 2, getPreferredTextColor());

		// The dot sits at the far right, inside the button's row.
		int dotX = x + entryWidth - 14;
		extractor.text(font, Component.literal("\u25cf"), dotX, y + (entryHeight - 8) / 2, current.color());

		int buttonWidth = ConfigUiMetrics.STANDARD_BUTTON_WIDTH;
		button.setWidth(buttonWidth);
		button.setX(dotX - buttonWidth - 10);
		button.setY(y + (entryHeight - button.getHeight()) / 2);
		button.setTooltip(Tooltip.create(Component.literal("连接测试：" + current.label())));
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
