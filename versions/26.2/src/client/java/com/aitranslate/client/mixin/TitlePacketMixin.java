package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Title and subtitle translation ({@code /title title}, {@code /title subtitle}).
 * <p>
 * 26.1 draws the HUD through {@code Gui#extractTitle}, which reads the
 * {@code title}/{@code subtitle} fields and hands them to the render-state
 * extractor. Redirecting that call keeps the packet data, the command and the
 * {@code Gui} fields untouched: only the component that is about to be drawn is
 * replaced, so the title keeps its position, scale and fade animation.
 */
@Mixin(Gui.class)
public abstract class TitlePacketMixin {

	@Redirect(method = "extractTitle", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"))
	private void aiTranslate$title(GuiGraphicsExtractor extractor, Font font, Component component, int x, int y,
			int width, int color) {
		extractor.textWithBackdrop(font, TranslationSupport.translated(component, TextType.TITLE), x, y, width, color);
	}

	/**
	 * Backdrop width of the subtitle (third feedback round, "不同语言长度差异").
	 * <p>
	 * The subtitle is measured before it is drawn, and that measurement positions
	 * the text and sizes its backdrop. Measuring the translation instead keeps a
	 * short Chinese subtitle centred instead of leaving it inside a box measured
	 * for the English original.
	 */
	@Redirect(method = "extractTitle", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
	private int aiTranslate$titleWidth(Font font, net.minecraft.network.chat.FormattedText text) {
		if (text instanceof Component component) {
			return font.width((net.minecraft.network.chat.FormattedText)
					TranslationSupport.translated(component, TextType.TITLE));
		}
		return font.width(text);
	}
}
