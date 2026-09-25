package com.aitranslate.client.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown when Cloth Config is not installed.
 * <p>
 * The mod keeps working without Cloth (defaults plus the {@code /aitranslate}
 * commands), so instead of failing to load the mod or crashing on a missing class,
 * the config entry points open this small vanilla screen and explain what to do.
 * It uses no Cloth Config class at all, which is the whole point.
 */
public class MissingClothScreen extends Screen {
	private final Screen parent;

	public MissingClothScreen(Screen parent) {
		super(Component.literal("AI Translate 配置"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
				.bounds(this.width / 2 - ConfigUiMetrics.STANDARD_BUTTON_WIDTH / 2, this.height / 2 + 40,
						ConfigUiMetrics.STANDARD_BUTTON_WIDTH, ConfigUiMetrics.BUTTON_HEIGHT)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);
		int centerX = this.width / 2;
		extractor.centeredText(this.font, Component.literal("未安装 Cloth Config"), centerX, this.height / 2 - 50,
				0xFFFFFF);
		extractor.centeredText(this.font, Component.literal("配置界面需要 Cloth Config；没有它也可以用命令修改设置："),
				centerX, this.height / 2 - 20, 0xAAAAAA);
		extractor.centeredText(this.font, Component.literal("/aitranslate toggle | reload | perf | cache ..."),
				centerX, this.height / 2 - 6, 0xAAAAAA);
		extractor.centeredText(this.font, Component.literal("翻译本身不受影响：默认设置即可正常工作。"), centerX,
				this.height / 2 + 14, 0xAAAAAA);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(this.parent);
		}
	}
}
