package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Text of hover events ({@code Component} styles with {@code hoverEvent},
 * third feedback round).
 * <p>
 * A hover tooltip is not a list of components: {@code componentHoverEffect}
 * reads {@code Style#getHoverEvent()} and, for {@code ShowText}, splits the
 * component into lines and hands those to the renderer. So there is no component
 * argument left to replace at the drawing call - the style itself is replaced on
 * its way into the effect, one step earlier.
 * <p>
 * Because the substitution happens before the line splitting, the tooltip box is
 * measured from the translation: a Chinese hover text gets a Chinese-sized box
 * instead of the box of the English original.
 * <p>
 * Only {@code ShowText} is rewritten. {@code ShowItem} and {@code ShowEntity}
 * carry an item stack or an entity id and reach the tooltip through the ordinary
 * tooltip overloads, which are handled by {@link TooltipMixin}.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class HoverTooltipMixin {

	@ModifyVariable(method = "componentHoverEffect", at = @At("HEAD"), argsOnly = true)
	private Style aiTranslate$hoverTooltip(Style style) {
		if (style == null || !TranslationSupport.isActive(TextType.TOOLTIP)) {
			return style;
		}
		HoverEvent hover = style.getHoverEvent();
		if (!(hover instanceof HoverEvent.ShowText showText) || showText.value() == null) {
			return style;
		}
		Component original = showText.value();
		Component translated = TranslationSupport.translated(original, TextType.TOOLTIP);
		if (translated == original) {
			return style;
		}
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"hover:" + original.getString() + "->" + translated.getString(),
					"[hook] HOVER '{}' -> '{}'", original.getString(), translated.getString());
		}
		return style.withHoverEvent(new HoverEvent.ShowText(translated));
	}
}
