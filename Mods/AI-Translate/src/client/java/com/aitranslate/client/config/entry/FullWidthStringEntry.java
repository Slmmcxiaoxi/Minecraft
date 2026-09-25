package com.aitranslate.client.config.entry;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import me.shedaniel.clothconfig2.gui.entries.TextFieldListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * A two row text entry: the label (with a hint) on the first row, a full width input
 * field on the second one.
 * <p>
 * The default Cloth text entry puts the label and the field on the same row, so long
 * values (API keys, base URLs, cache paths) are cut off and cannot be read. This entry
 * gives the field the full width of the config list.
 * <p>
 * <strong>Sixteenth feedback round - what was removed and why.</strong> The entry used
 * to carry two extra buttons on the second row:
 *
 * <ul>
 * <li>a 显示 / 隐藏 button that switched a secret between masked and plain text. The
 * feedback document reports that this feature fights with the collapsed sub-category the
 * API Key lives in, and asks for it to go: the value is <em>always</em> shown masked now
 * and the button is gone;</li>
 * <li>Cloth's reset button (the arrow that restores the default). It caused the same kind
 * of trouble for the API Key, the API URL and the model name, so <strong>no</strong>
 * full-width field has one any more. The value is edited in the field, and the shipped
 * default is written in the hint row under the label.</li>
 * </ul>
 *
 * Everything the field has to keep doing is unchanged: typing, deleting, selecting,
 * pasting and saving the value. Masking is display only - {@code getValue()} returns the
 * real text, so the API key that reaches the config file is the one the player typed.
 * <p>
 * <strong>Deprecation:</strong> every {@code TextFieldListEntry} constructor is marked
 * deprecated in Cloth Config 26.1, without a replacement API - subclassing the entry is
 * the only way to get a two row field, so the warning is suppressed here (it is about
 * Cloth's own API, not about this code) and this note is the record of it.
 * <p>
 * <strong>Cloth Config is a required dependency</strong> since the fifteenth feedback
 * round (the config screen, the cache page and the key binding rows all live in it).
 */
@SuppressWarnings("deprecation")
public class FullWidthStringEntry extends TextFieldListEntry<String> {
	private static final Component RESET_KEY = Component.translatable("text.cloth-config.reset_value");

	/** The field of a secret is always masked; the others are plain. */
	private final boolean secret;
	private final Component tooltip;

	public FullWidthStringEntry(Component fieldName, String value, Supplier<String> defaultValue,
			Consumer<String> saveCallback, boolean secret, Component tooltip) {
		// The shorter Cloth constructors are deprecated in 26.1; the current one
		// takes the tooltip supplier and the "requires restart" flag. Both defaults
		// reproduce what the deprecated overloads did: no Cloth tooltip (this entry
		// draws its own hint row, see extractRenderState) and no restart needed.
		super(fieldName, value, RESET_KEY, defaultValue, null, false);
		this.saveCallback = saveCallback;
		this.secret = secret;
		this.tooltip = tooltip;

		// Fifteenth feedback round, root cause of "the API Key field cannot be typed
		// into": Cloth's TextFieldListEntry creates its widget list but never puts the
		// text field into it, and this entry's children() is exactly that list. A widget
		// that is not a child never receives clicks, so the field could never take focus
		// and every keystroke went nowhere - clicking it looked dead and backspace did
		// nothing.
		this.widgets.add(this.textFieldWidget);

		if (secret) {
			// Display only: the formatter changes what is drawn, never the value.
			this.textFieldWidget.addFormatter(this::mask);
		}
	}

	private FormattedCharSequence mask(String text, int cursor) {
		if (text.isEmpty()) {
			return FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY);
		}
		return FormattedCharSequence.forward("•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY);
	}

	@Override
	public String getValue() {
		return textFieldWidget.getValue();
	}

	/**
	 * No default value, so Cloth draws no reset arrow next to the field.
	 * <p>
	 * Seventeenth feedback round: the document asks for the per-entry resets to be gone
	 * (only 恢复默认设置 and the button position's own 重置 stay). Cloth decides whether to
	 * draw the arrow from this method, so an empty value removes it - the shipped default
	 * is written in the hint row under the label instead.
	 */
	@Override
	public Optional<String> getDefaultValue() {
		return Optional.empty();
	}

	@Override
	public int getItemHeight() {
		return 44;
	}

	/**
	 * Two row layout: label on the first row, a full width field on the second.
	 * <p>
	 * Cloth's parameter order is {@code (index, y, x, entryWidth, entryHeight,
	 * mouseX, mouseY, isSelected, delta)}; the base implementation is not called
	 * because it lays the label and the field out on a single row.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean isSelected, float delta) {
		Font font = Minecraft.getInstance().font;
		// Row 1: label, with the hint underneath it.
		extractor.text(font, getFieldName(), x + 4, y + 2, getPreferredTextColor());
		if (tooltip != null) {
			extractor.text(font, tooltip, x + 4, y + 13, 0xFF909090);
		}

		// Row 2: the input field spans the whole entry - there is no button next to it any
		// more (sixteenth feedback round), which is also what makes the field as wide as
		// the list.
		int fieldY = y + 24;
		int fieldHeight = 18;
		int fieldWidth = Math.max(80, entryWidth - 8);
		setTextFieldWidth(textFieldWidget, fieldWidth);
		textFieldWidget.setX(x + 4);
		textFieldWidget.setY(fieldY);
		textFieldWidget.setHeight(fieldHeight);
		textFieldWidget.extractRenderState(extractor, mouseX, mouseY, delta);
	}

	/**
	 * Only the text field is a child of this entry.
	 * <p>
	 * Sixteenth feedback round: Cloth's own reset button is created by the base class and
	 * put into its widget list, and the child list is exactly what the click dispatch
	 * walks - so the invisible reset button was <em>clickable</em> at the position it is
	 * created with, and it reset the API key to its default when the player clicked into
	 * that corner of the screen. The probe caught it: clicking at (12, 10) on the API page
	 * emptied the field. The button is therefore kept out of the child list (and out of
	 * the narration list); it is also never drawn, so it cannot be used at all.
	 */
	@Override
	public List<? extends GuiEventListener> children() {
		return List.of(textFieldWidget);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.of(textFieldWidget);
	}
}
