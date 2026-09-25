package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Widget tooltips - buttons, checkboxes, tabs, list entries (third feedback
 * round: "悬停在按钮上显示的提示文本").
 * <p>
 * These never reach the extractor as components: {@link Tooltip} converts its
 * message into {@link FormattedCharSequence}s with {@code splitTooltip} and hands
 * those to the renderer. The message is therefore translated right before it is
 * split, which also means the tooltip box is measured from the translation.
 * <p>
 * {@code cachedTooltip} keeps the split result and is only invalidated when the
 * display language changes, so an already hovered button would keep the original
 * text forever; it is dropped when the render generation changed (a translation
 * arrived, the master switch was flipped).
 */
@Mixin(Tooltip.class)
public abstract class WidgetTooltipMixin {

	@Shadow
	private List<FormattedCharSequence> cachedTooltip;

	@Shadow
	private Language splitWithLanguage;

	@Unique
	private long aiTranslate$generation = Long.MIN_VALUE;

	@ModifyVariable(method = "splitTooltip", at = @At("HEAD"), argsOnly = true)
	private static Component aiTranslate$splitTooltip(Component message) {
		Component translated = TranslationSupport.translated(message, TextType.TOOLTIP);
		if (translated != message && com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"widgettooltip:" + message.getString() + "->" + translated.getString(),
					"[hook] WIDGET_TOOLTIP '{}' -> '{}'", message.getString(), translated.getString());
		}
		return translated;
	}

	@Inject(method = "toCharSequence", at = @At("HEAD"))
	private void aiTranslate$invalidateCache(Minecraft client, CallbackInfoReturnable<List<FormattedCharSequence>> info) {
		long generation = TranslationSupport.generation();
		if (aiTranslate$generation == generation) {
			return;
		}
		aiTranslate$generation = generation;
		this.cachedTooltip = null;
		this.splitWithLanguage = null;
	}
}
