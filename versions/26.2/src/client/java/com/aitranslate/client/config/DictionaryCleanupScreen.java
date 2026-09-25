package com.aitranslate.client.config;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.dictionary.DictionaryManager.Scope;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit confirmation before deleting current or all dictionary files. */
public final class DictionaryCleanupScreen extends Screen {
	private final Screen parent;
	private final boolean all;

	public DictionaryCleanupScreen(Screen parent, boolean all) {
		super(Component.literal(all ? "清理全部词典" : "清理当前词典"));
		this.parent = parent;
		this.all = all;
	}

	@Override protected void init() {
		int y = height / 2 + 18;
		int buttonWidth = ConfigUiMetrics.STANDARD_BUTTON_WIDTH;
		int gap = ConfigUiMetrics.GAP;
		addRenderableWidget(Button.builder(Component.literal("确认清理"), b -> clear())
				.bounds(width / 2 - buttonWidth - gap / 2, y, buttonWidth, ConfigUiMetrics.BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
				.bounds(width / 2 + gap / 2, y, buttonWidth, ConfigUiMetrics.BUTTON_HEIGHT).build());
	}

	private void clear() {
		var manager = AITranslateModClient.dictionaryManager;
		if (manager != null) {
			if (all) manager.clearAll(); else manager.clear(Scope.CURRENT);
		}
		onClose();
	}

	@Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
	@Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
		super.extractBackground(g, mx, my, tick); g.fill(0, 0, width, height, 0xB0000000);
	}
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
		super.extractRenderState(g, mx, my, tick);
		g.centeredText(Minecraft.getInstance().font,
				all ? "确认清理全部当前/全局词典？普通翻译缓存不会删除。" : "确认清理当前世界或服务器的词典？",
				width / 2, height / 2 - 18, 0xFFFFFFFF);
	}
}
