package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.aitranslate.client.display.ContextualText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Dialog, book and advancement text drawn straight through
 * {@code GuiGraphicsExtractor#text}.
 * <p>
 * Everything the GUI draws as a component funnels through those methods in 26.1,
 * which makes them the single hook for "a screen wants to draw a piece of text". The
 * decision which screen it belongs to lives in {@link ContextualText}, together with
 * the same decision for widget labels - so a screen is either supported by both
 * hooks or by neither.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
			at = @At("HEAD"), argsOnly = true)
	private Component aiTranslate$text(Component component) {
		return ContextualText.forCurrentScreen(component);
	}

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
			at = @At("HEAD"), argsOnly = true)
	private Component aiTranslate$textShadow(Component component) {
		return ContextualText.forCurrentScreen(component);
	}

	@ModifyVariable(method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
			at = @At("HEAD"), argsOnly = true)
	private Component aiTranslate$centeredText(Component component) {
		return ContextualText.forCurrentScreen(component);
	}
}
