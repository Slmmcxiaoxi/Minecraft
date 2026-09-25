package com.aitranslate.client.config.entry;

import java.util.List;
import java.util.Optional;

import org.lwjgl.glfw.GLFW;

import com.aitranslate.client.keybinding.KeyCaptureManager;
import com.aitranslate.client.keybinding.SingleKeyBinding;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A Cloth Config row that captures exactly one key. ESC cancels; Delete clears. */
public class SingleKeyEntry extends AbstractConfigListEntry<SingleKeyBinding> {
	public static final int ROW_HEIGHT = 24;
	public static final int FILL_COLUMN = -1;
	private static final Component LISTENING = Component.literal("按下按键…（ESC 取消，Delete 清除）");

	private final java.util.function.Supplier<SingleKeyBinding> getter;
	private final java.util.function.Consumer<SingleKeyBinding> setter;
	private final int fixedBoxWidth;
	private boolean listening;
	private int boxX;
	private int boxY;
	private int boxWidth;
	private int boxHeight;

	public SingleKeyEntry(Component name, java.util.function.Supplier<SingleKeyBinding> getter,
			java.util.function.Consumer<SingleKeyBinding> setter) {
		this(name, getter, setter, 0);
	}

	public SingleKeyEntry(Component name, java.util.function.Supplier<SingleKeyBinding> getter,
			java.util.function.Consumer<SingleKeyBinding> setter, int fixedBoxWidth) {
		super(name, false);
		this.getter = getter;
		this.setter = setter;
		this.fixedBoxWidth = fixedBoxWidth == FILL_COLUMN ? FILL_COLUMN : Math.max(0, fixedBoxWidth);
	}

	@Override
	public SingleKeyBinding getValue() {
		SingleKeyBinding value = getter.get();
		return value == null ? SingleKeyBinding.unbound() : value;
	}

	@Override
	public Optional<SingleKeyBinding> getDefaultValue() {
		return Optional.empty();
	}

	@Override
	public int getItemHeight() {
		return ROW_HEIGHT;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int index, int y, int x, int entryWidth,
			int entryHeight, int mouseX, int mouseY, boolean selected, float delta) {
		Font font = Minecraft.getInstance().font;
		extractor.text(font, getFieldName(), x + 4, y + (entryHeight - 8) / 2, getPreferredTextColor());
		boxWidth = fixedBoxWidth == FILL_COLUMN ? Math.max(48, entryWidth)
				: fixedBoxWidth > 0 ? Math.max(48, Math.min(fixedBoxWidth, Math.max(48, entryWidth - 8)))
				: Math.min(220, Math.max(120, entryWidth / 2));
		boxX = fixedBoxWidth == FILL_COLUMN ? x : x + entryWidth - boxWidth - 4;
		boxY = y + 2;
		boxHeight = Math.max(16, entryHeight - 4);
		boolean hovered = contains(mouseX, mouseY);
		extractor.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight,
				listening ? 0xC0504000 : hovered ? 0xC0383838 : 0xC0202020);
		extractor.outline(boxX, boxY, boxWidth, boxHeight, listening ? 0xFFFFD700 : 0xFF808080);
		Component value = listening ? LISTENING : Component.literal(getValue().describe());
		extractor.text(font, value, boxX + 6, boxY + (boxHeight - 8) / 2,
				listening ? 0xFFFFD700 : 0xFFFFFFFF);
	}

	private boolean contains(int mouseX, int mouseY) {
		return mouseX >= boxX && mouseX < boxX + boxWidth && mouseY >= boxY && mouseY < boxY + boxHeight;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (!listening && !contains((int) event.x(), (int) event.y())) {
			return false;
		}
		if (!listening) {
			listening = true;
			KeyCaptureManager.begin(new KeyCaptureManager.Listener() {
				@Override
				public boolean onKeyPressed(int keyCode) {
					if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
						cancel();
					} else {
						commit(keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE
								? SingleKeyBinding.UNBOUND : keyCode);
					}
					return true;
				}

				@Override
				public void onCancelled() {
					listening = false;
				}
			});
		}
		return true;
	}

	private void commit(int keyCode) {
		listening = false;
		KeyCaptureManager.stop();
		setter.accept(new SingleKeyBinding(keyCode));
	}

	private void cancel() {
		listening = false;
		KeyCaptureManager.stop();
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return List.of();
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.of();
	}

	public int boxX() {
		return boxX;
	}

	public int boxWidth() {
		return boxWidth;
	}
}
