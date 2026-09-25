package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Action bar translation ({@code /title actionbar}, overlay messages).
 * <p>
 * Only the component handed to the render-state extractor is replaced, so the
 * action bar keeps its position, colour animation and fade timing.
 */
@Mixin(Gui.class)
public abstract class OverlayMessageMixin {
	/**
	 * Handles locked-container feedback at receipt time as well as draw time. A slow
	 * API may answer after vanilla's short action-bar timer expired; the completion
	 * callback installs the translated component and restarts that timer once.
	 */
	@ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component aiTranslate$lockedContainerMessage(Component component) {
		if (aiTranslate$type(component) != TextType.CONTAINER || TranslationSupport.isDisplay(component)
				|| com.aitranslate.client.AITranslateModClient.scheduler == null) {
			return component;
		}
		java.util.List<String> texts = com.aitranslate.client.capture.ComponentExtractor.uniqueTexts(component);
		for (String text : texts) {
			com.aitranslate.client.AITranslateModClient.scheduler.request(TextType.CONTAINER, text,
					com.aitranslate.client.scheduler.TranslationPriority.HIGH, () -> {
				var client = net.minecraft.client.Minecraft.getInstance();
				Component ready = TranslationSupport.translated(component, TextType.CONTAINER);
				if (client != null && client.gui != null && ready != component) {
					client.gui.setOverlayMessage(ready, false);
				}
				}, "locked container");
		}
		return TranslationSupport.translated(component, TextType.CONTAINER);
	}

	@Redirect(method = "extractOverlayMessage", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"))
	private void aiTranslate$overlayMessage(GuiGraphicsExtractor extractor, Font font, Component component, int x,
			int y, int width, int color) {
		extractor.textWithBackdrop(font, TranslationSupport.translated(component, aiTranslate$type(component)), x, y, width,
				color);
	}

	/**
	 * Backdrop width of the action bar (third feedback round, "不同语言长度差异").
	 * <p>
	 * The width and the x position of the action bar are computed from the original
	 * component before it is drawn, so replacing only the drawn text would draw a
	 * short Chinese line inside a box measured for the English original (or clip a
	 * longer translation). Measuring the translation keeps the box and the
	 * centring consistent with the text that is actually shown.
	 */
	@Redirect(method = "extractOverlayMessage", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
	private int aiTranslate$overlayMessageWidth(Font font, net.minecraft.network.chat.FormattedText text) {
		if (text instanceof Component component) {
			return font.width((net.minecraft.network.chat.FormattedText)
					TranslationSupport.translated(component, aiTranslate$type(component)));
		}
		return font.width(text);
	}

	/** Locked-container feedback carries the custom container name as argument 0. */
	@org.spongepowered.asm.mixin.Unique
	private static TextType aiTranslate$type(Component component) {
		if (component != null && com.aitranslate.client.util.ComponentDiagnostics
				.containsTranslationKey(component, "container.isLocked")) {
			return TextType.CONTAINER;
		}
		return TextType.ACTION_BAR;
	}
}
