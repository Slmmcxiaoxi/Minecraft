package com.aitranslate.client.config.entry;

import java.util.List;
import java.util.Optional;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * One row carrying the three controls of the in-screen switch (fifteenth feedback round:
 * 全局 → 按键绑定++ → 界面内切换), laid out as
 * {@code [绑定按键] [界面按钮：是/否] [位置]}.
 * <p>
 * Cloth has no layout for "three controls of different kinds on one row":
 * {@code MultiElementListEntry} renders its children stacked under a category header, and
 * a sub-category adds another level of nesting. This entry therefore draws the label
 * itself and lays the controls out from the right edge, using the <em>same</em> geometry
 * as a plain key binding row:
 *
 * <ul>
 * <li><strong>height (sixteenth feedback round, 1.1):</strong> the row is exactly as tall
 * as a key binding row ({@link SingleKeyEntry#getItemHeight()}), and the controls are
 * vertically centred inside it - before this the row was 30 px tall while every other
 * binding row was 24 px, which is what made it look out of place;</li>
 * <li><strong>width (1.2):</strong> the three controls together are exactly as wide as the
 * binding box of the other rows and end on the same right edge
 * ({@code x + entryWidth - 4}). Inside that width the binding box gets 50 % and the two
 * buttons 25 % each, with two equal gaps - the "整行对齐" the document asks for.</li>
 * </ul>
 */
public class SwitchRowEntry extends AbstractConfigListEntry<Boolean> {

	/** Gap between the three controls (also used by the width split). */
	private static final int GAP = 4;
	/** Right margin of the row, identical to {@link SingleKeyEntry}. */
	private static final int RIGHT_MARGIN = 4;
	/** Width the 界面按钮 control needs so its label is not clipped. */
	private static final int TOGGLE_MIN = 76;
	/** Width the 位置 control needs. */
	private static final int ADJUST_MIN = 44;
	/** Smallest sensible width for the binding box before the ratio split takes over. */
	private static final int BINDING_MIN = 72;

	private final SingleKeyEntry binding;
	private final Button guiToggle;
	private final Button adjust;
	private final java.util.function.Consumer<Boolean> showButtonSetter;
	/** The state the 是/否 button shows; the setter applies it to the config. */
	private boolean shown;

	public SwitchRowEntry(Component fieldName, SingleKeyEntry binding, boolean showButton,
			java.util.function.Consumer<Boolean> showButtonSetter, Runnable openPositionScreen) {
		super(fieldName, false);
		this.binding = binding;
		this.showButtonSetter = showButtonSetter;
		this.shown = showButton;
		this.guiToggle = Button.builder(toggleLabel(shown), pressed -> toggle())
				.bounds(0, 0, 1, com.aitranslate.client.config.ConfigUiMetrics.BUTTON_HEIGHT).build();
		this.guiToggle.setTooltip(Tooltip.create(Component.literal("是否在书籍 / 对话框 / 聊天等界面显示这个按钮")));
		this.adjust = Button.builder(Component.literal("位置"), pressed -> openPositionScreen.run())
				.bounds(0, 0, 1, com.aitranslate.client.config.ConfigUiMetrics.BUTTON_HEIGHT).build();
		this.adjust.setTooltip(Tooltip.create(Component.literal("打开拖动界面，把按钮放到顺手的位置")));
	}

	private static Component toggleLabel(boolean show) {
		return Component.literal("界面按钮：" + (show ? "是" : "否"));
	}

	/** Flips the switch, tells the config and updates the label immediately. */
	private void toggle() {
		shown = !shown;
		guiToggle.setMessage(toggleLabel(shown));
		showButtonSetter.accept(shown);
	}

	@Override
	public Boolean getValue() {
		return shown;
	}

	@Override
	public Optional<Boolean> getDefaultValue() {
		// The page-level “恢复默认设置” is the only reset control.
		return Optional.empty();
	}

	/** The height of a key binding row, so both kinds of row line up. */
	@Override
	public int getItemHeight() {
		return SingleKeyEntry.ROW_HEIGHT;
	}

	/**
	 * Cloth's parameter order is {@code (index, y, x, entryWidth, entryHeight,
	 * mouseX, mouseY, isSelected, delta)}.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean isSelected, float delta) {
		Font font = Minecraft.getInstance().font;
		extractor.text(font, getFieldName(), x + 4, y + (entryHeight - 8) / 2, getPreferredTextColor());

		// The width a plain key binding row gives its binding box: exactly the same
		// formula SingleKeyEntry uses, so the right edges of all rows line up.
		int total = Math.min(220, Math.max(120, entryWidth / 2));
		int right = x + entryWidth - RIGHT_MARGIN;
		int inner = Math.max(3 * 24 + 2 * GAP, total - 2 * GAP);
		// The document's example split is 50 % binding box / 25 % button / 25 % position.
		// At the standard width that is 106 / 53 / 53 px, and "界面按钮：是" does not fit in
		// 53 px (it would be drawn clipped). The two buttons therefore get the width their
		// labels need, the binding box gets the rest, and when the row is too narrow for
		// those minimums everything is scaled down proportionally - the sum is exactly the
		// width of a plain binding box in every case, which is what the alignment requires.
		int toggleWidth = Math.min(TOGGLE_MIN, inner / 2);
		int adjustWidth = Math.min(ADJUST_MIN, Math.max(24, (inner - toggleWidth) / 2));
		int bindingWidth = inner - toggleWidth - adjustWidth;
		if (bindingWidth < BINDING_MIN) {
			// Very narrow row: share what there is in the documented 50 / 25 / 25 ratio.
			bindingWidth = inner / 2;
			toggleWidth = (inner - bindingWidth) / 2;
			adjustWidth = inner - bindingWidth - toggleWidth;
		}

		int adjustX = right - adjustWidth;
		int toggleX = adjustX - GAP - toggleWidth;
		int bindingX = toggleX - GAP - bindingWidth;

		// All three controls share the same vertical centre as the binding box of the
		// other rows (which is drawn at y + 2 with the row height minus 4).
		int buttonY = y + 2;
		int buttonHeight = Math.max(16, entryHeight - 4);

		adjust.setWidth(adjustWidth);
		adjust.setHeight(buttonHeight);
		adjust.setX(adjustX);
		adjust.setY(buttonY);
		adjust.extractRenderState(extractor, mouseX, mouseY, delta);

		guiToggle.setWidth(toggleWidth);
		guiToggle.setHeight(buttonHeight);
		guiToggle.setX(toggleX);
		guiToggle.setY(buttonY);
		guiToggle.extractRenderState(extractor, mouseX, mouseY, delta);

		// The binding box draws its own frame; it is given a column that ends where the
		// 界面按钮 control starts.
		binding.extractRenderState(extractor, index, y, bindingX, bindingWidth, entryHeight, mouseX, mouseY,
				isSelected, delta);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (adjust.mouseClicked(event, doubled)) {
			return true;
		}
		if (guiToggle.mouseClicked(event, doubled)) {
			return true;
		}
		return binding.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = adjust.mouseReleased(event);
		handled |= guiToggle.mouseReleased(event);
		handled |= binding.mouseReleased(event);
		return handled;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return binding.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return binding.charTyped(event);
	}

	/** Commits the child controls when Cloth saves the page. */
	@Override
	public void save() {
		binding.save();
		super.save();
	}

	@Override
	public List<? extends GuiEventListener> children() {
		// Explicit casts: the three controls are unrelated classes, and the event
		// dispatch of Cloth walks exactly this list.
		return List.of((GuiEventListener) binding, (GuiEventListener) guiToggle, (GuiEventListener) adjust);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.of((NarratableEntry) binding, (NarratableEntry) guiToggle, (NarratableEntry) adjust);
	}

	// ------------------------------------------------ accessors (self test probe)

	/**
	 * Left edge of the binding box as the last frame laid it out.
	 * <p>
	 * The binding box draws its own frame (it is an entry, not a widget), so the self test
	 * cannot read it from the child list - this is how it proves that the three controls
	 * together span exactly the width of a plain binding box (sixteenth round, 1.2).
	 */
	public int bindingBoxX() {
		return binding.boxX();
	}

	/** Width of the binding box as the last frame laid it out. */
	public int bindingBoxWidth() {
		return binding.boxWidth();
	}

	/** Right edge of the last control of the row (the 位置 button). */
	public int groupRight() {
		return adjust.getX() + adjust.getWidth();
	}
}
