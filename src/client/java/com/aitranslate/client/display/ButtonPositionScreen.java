package com.aitranslate.client.display;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The "调整位置" screen of the in-screen translation button (fifteenth feedback
 * round).
 * <p>
 * The feedback document asks for a dedicated, blank screen with a draggable button
 * instead of typing coordinates into a text field, plus boundary lines, a live
 * coordinate readout and 保存 / 取消 at the bottom. This screen is that:
 *
 * <ul>
 * <li>the background is a plain dark overlay - no world, no other widgets, so it is
 * obvious which button is being moved;</li>
 * <li>the button is dragged with the mouse and clamped to the screen, and it shows a
 * highlight border while it is held;</li>
 * <li>the screen edges are drawn as boundary lines, so "at the edge" is visible
 * rather than guessed;</li>
 * <li>the readout shows the live coordinates, the anchor that release will snap to
 * and the offsets that will be stored;</li>
 * <li>保存 writes the anchor and the offsets to {@link ModConfig} and to the config
 * file; 取消 leaves everything as it was.</li>
 * </ul>
 *
 * Dragging is handled by this screen itself and never forwarded to the dragged
 * widget: a real {@code Button} would fire its action on release, so the preview is
 * a button that does nothing and its mouse events stop here.
 */
public class ButtonPositionScreen extends Screen {

	private static final int MARGIN = 4;
	private static final int BAR_HEIGHT = 24;
	private static final int DARK = 0xC0000000;
	private static final int BOUNDARY = 0x60A0A0A0;
	private static final int ANCHOR_MARK = 0x50808080;
	private static final int DRAG_BORDER = 0xFFFFD700;
	private static final int IDLE_BORDER = 0xFFE0E0E0;

	private final Screen parent;

	/** The position being edited; only 保存 writes it back to the config. */
	private ButtonPlacement.Anchor anchor;
	private int offsetX;
	private int offsetY;

	private Button preview;
	private boolean dragging;
	private int grabX;
	private int grabY;

	public ButtonPositionScreen(Screen parent) {
		super(Component.literal("调整界面内按钮位置"));
		this.parent = parent;
		ModConfig config = AITranslateModClient.config;
		this.anchor = config == null ? ButtonPlacement.Anchor.TOP_RIGHT
				: ButtonPlacement.Anchor.parseOr(config.buttonAnchor, ButtonPlacement.Anchor.TOP_RIGHT);
		this.offsetX = config == null ? -10 : config.buttonOffsetX;
		this.offsetY = config == null ? 10 : config.buttonOffsetY;
	}

	@Override
	protected void init() {
		preview = ScreenToggleButton.createPreview();
		ButtonPlacement.Position position = ButtonPlacement.place(anchor, offsetX, offsetY, width, height,
				ScreenToggleButton.WIDTH, ScreenToggleButton.HEIGHT);
		preview.setX(position.x());
		preview.setY(position.y());
		// Renderable but not a click target: see the class comment (a real button would
		// fire on release). It is still added as a widget so the game draws it.
		addRenderableWidget(preview);

		int barY = height - BAR_HEIGHT - MARGIN;
		int buttonWidth = Math.min(90, Math.max(60, width / 6));
		int gap = 6;
		int total = buttonWidth * 2 + gap;
		int startX = (width - total) / 2;
		addRenderableWidget(Button.builder(Component.literal("保存"), pressed -> save())
				.bounds(startX, barY, buttonWidth, com.aitranslate.client.config.ConfigUiMetrics.BUTTON_HEIGHT)
				.build());
		addRenderableWidget(Button.builder(Component.literal("取消"), pressed -> cancel())
				.bounds(startX + buttonWidth + gap, barY, buttonWidth,
						com.aitranslate.client.config.ConfigUiMetrics.BUTTON_HEIGHT)
				.build());
	}

	/** The dark, empty background the feedback document asks for. */
	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		// Deliberately not calling super: no panorama, no world behind the drag screen.
		extractor.fill(0, 0, width, height, DARK);
	}

	/**
	 * Boundary lines, anchor marks and the live readout.
	 * <p>
	 * Drawn after the widgets (this method runs after the widget pass), so the
	 * highlight border of a dragged button ends up on top of the button instead of
	 * behind it.
	 */
	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);

		// Boundary lines: the rectangle the button can be dragged inside, plus the four
		// anchor corners and the centre as small marks.
		extractor.outline(MARGIN - 1, MARGIN - 1, width - (MARGIN - 1) * 2, height - (MARGIN - 1) * 2, BOUNDARY);
		for (ButtonPlacement.Anchor candidate : ButtonPlacement.Anchor.values()) {
			ButtonPlacement.Position mark = ButtonPlacement.base(candidate, width, height,
					ScreenToggleButton.WIDTH, ScreenToggleButton.HEIGHT);
			extractor.fill(mark.x() - 3, mark.y() - 3, mark.x() + 3, mark.y() + 3, ANCHOR_MARK);
		}

		if (preview != null) {
			extractor.outline(preview.getX() - 1, preview.getY() - 1, ScreenToggleButton.WIDTH + 2,
					ScreenToggleButton.HEIGHT + 2, dragging ? DRAG_BORDER : IDLE_BORDER);
		}

		ButtonPlacement.Anchor snapped = anchorAt(preview);
		ButtonPlacement.Position offsets = ButtonPlacement.offsets(snapped, preview.getX(), preview.getY(), width,
				height, ScreenToggleButton.WIDTH, ScreenToggleButton.HEIGHT);

		var font = Minecraft.getInstance().font;
		extractor.text(font, "拖动按钮到任意位置，松手后吸附到最近的角", MARGIN + 4, MARGIN + 6, 0xFFFFFFFF);
		extractor.text(font, "当前位置：" + ButtonPlacement.describe(snapped, offsets.x(), offsets.y())
				+ "   坐标：(" + preview.getX() + ", " + preview.getY() + ")", MARGIN + 4, MARGIN + 20,
				0xFFE0E0E0);
		extractor.text(font, "屏幕 " + width + " x " + height,
				MARGIN + 4, MARGIN + 34, 0xFF909090);
	}

	// ------------------------------------------------------------------ dragging

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		if (preview != null && event.button() == 0 && preview.isMouseOver(event.x(), event.y())) {
			dragging = true;
			grabX = (int) event.x() - preview.getX();
			grabY = (int) event.y() - preview.getY();
			setDragging(true);
			return true;
		}
		return super.mouseClicked(event, doubled);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!dragging || preview == null) {
			return super.mouseDragged(event, dragX, dragY);
		}
		ButtonPlacement.Position moved = ButtonPlacement.clamp((int) event.x() - grabX, (int) event.y() - grabY,
				width, height, ScreenToggleButton.WIDTH, ScreenToggleButton.HEIGHT);
		preview.setX(moved.x());
		preview.setY(moved.y());
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (!dragging) {
			return super.mouseReleased(event);
		}
		dragging = false;
		setDragging(false);
		// Snap to the nearest corner and keep the rest as an offset: the stored value
		// then follows the window edges instead of one window size.
		ButtonPlacement.Anchor snapped = anchorAt(preview);
		ButtonPlacement.Position offsets = ButtonPlacement.offsets(snapped, preview.getX(), preview.getY(), width,
				height, ScreenToggleButton.WIDTH, ScreenToggleButton.HEIGHT);
		anchor = snapped;
		offsetX = offsets.x();
		offsetY = offsets.y();
		return true;
	}

	/** The anchor the current position is nearest to (also used by the readout). */
	private ButtonPlacement.Anchor anchorAt(Button previewButton) {
		if (previewButton == null) {
			return anchor;
		}
		return ButtonPlacement.nearest(previewButton.getX(), previewButton.getY(), width, height,
				ScreenToggleButton.WIDTH, ScreenToggleButton.HEIGHT);
	}

	// ------------------------------------------------ accessors (self test probe)

	/** The draggable preview button. */
	public Button previewButton() {
		return preview;
	}

	/** The anchor that 保存 would write. */
	public String anchorId() {
		return anchor.id();
	}

	/** The offsets that 保存 would write. */
	public int offsetX() {
		return offsetX;
	}

	public int offsetY() {
		return offsetY;
	}

	/** True while the preview is being dragged (probe/diagnostics). */
	public boolean isDraggingPreview() {
		return dragging;
	}

	/** The 保存 action, for the self test (the button itself does the same). */
	public void savePosition() {
		save();
	}

	/** Writes the edited position to the config and back to disk. */
	private void save() {
		ModConfig config = AITranslateModClient.config;
		if (config != null) {
			config.buttonAnchor = anchor.id();
			config.buttonOffsetX = offsetX;
			config.buttonOffsetY = offsetY;
			config.save();
			AITranslateModClient.onConfigChanged();
		}
		Minecraft.getInstance().setScreen(parent);
	}

	private void cancel() {
		Minecraft.getInstance().setScreen(parent);
	}

	/**
	 * Esc behaves like 取消: the position is only written by 保存, so leaving the
	 * screen must not change anything.
	 */
	@Override
	public void onClose() {
		cancel();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
