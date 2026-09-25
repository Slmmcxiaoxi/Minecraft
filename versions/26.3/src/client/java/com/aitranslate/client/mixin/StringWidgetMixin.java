package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

/**
 * Single line widget labels - most importantly the <em>dialog title</em>
 * (fifth feedback round).
 * <p>
 * A {@code StringWidget} does not draw through {@code GuiGraphicsExtractor#text}:
 * it asks for a text collector ({@code textRendererForWidget}) and converts its
 * message to visual order / clipped lines itself. That is why the dialog body (a
 * {@code MultiLineTextWidget}, handled by {@code DialogScreenMixin}) was translated
 * while the dialog title - a {@code StringWidget} built by
 * {@code DialogScreen#createTitleWithWarningButton} - was not.
 * <p>
 * The message getter is redirected at the top of {@code visitLines}, so the
 * translation is what gets clipped, scrolled and measured - the layout follows the
 * translated text. Which screens are affected is decided by
 * {@link com.aitranslate.client.display.ContextualText}, the same helper the
 * extractor hook uses.
 */
@Mixin(StringWidget.class)
public abstract class StringWidgetMixin {

	@Shadow
	private boolean cachedWidthDirty;

	@Unique
	private long aiTranslate$generation = Long.MIN_VALUE;

	@Redirect(method = "visitLines", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/StringWidget;getMessage()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$widgetLabel(StringWidget widget) {
		Component original = widget.getMessage();
		Component translated = com.aitranslate.client.display.ContextualText.forCurrentScreen(original);
		if (translated != original && com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"widgetlabel:" + original.getString() + "->" + translated.getString(),
					"[hook] WIDGET_LABEL '{}' -> '{}'", original.getString(), translated.getString());
		}
		return translated;
	}

	/**
	 * The width used for centring and clipping (sixth feedback round: a longer or
	 * shorter translation kept the original width and the title ended up flush left).
	 * <p>
	 * {@code getWidth()} measures the message and caches the result, so the message
	 * getter is redirected here too - the measured width is the width of the text that
	 * is actually drawn. The cached value is dropped whenever the render generation
	 * changed, i.e. as soon as a translation arrives.
	 */
	@Redirect(method = "getWidth", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/StringWidget;getMessage()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$widthMessage(StringWidget widget) {
		long generation = TranslationSupport.generation();
		if (aiTranslate$generation != generation) {
			aiTranslate$generation = generation;
			this.cachedWidthDirty = true;
		}
		return com.aitranslate.client.display.ContextualText.forCurrentScreen(widget.getMessage());
	}
}
