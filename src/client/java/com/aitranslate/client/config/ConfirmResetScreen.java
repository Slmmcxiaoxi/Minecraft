package com.aitranslate.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Second confirmation for "reset all settings". */
public class ConfirmResetScreen extends Screen {
	private final Screen parent;

	public ConfirmResetScreen(Screen parent) {
		super(Component.literal("确认恢复默认设置？"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int y = this.height / 2 + 20;
		int buttonWidth = ConfigUiMetrics.STANDARD_BUTTON_WIDTH;
		int gap = ConfigUiMetrics.GAP;
		addRenderableWidget(Button.builder(Component.literal("恢复默认"), button -> {
			ConfigScreen.resetToDefaults();
			Minecraft.getInstance().setScreen(ConfigScreen.create(parent));
		}).bounds(centerX - buttonWidth - gap / 2, y, buttonWidth, ConfigUiMetrics.BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
				.bounds(centerX + gap / 2, y, buttonWidth, ConfigUiMetrics.BUTTON_HEIGHT).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);
		int centerX = this.width / 2;
		extractor.centeredText(this.font, this.title, centerX, this.height / 2 - 30, 0xFFFFFFFF);
		extractor.centeredText(this.font, Component.literal("所有配置项（含 API Key）都会恢复为默认值"),
				centerX, this.height / 2 - 10, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
