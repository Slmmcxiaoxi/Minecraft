package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.gui.components.AbstractTextAreaWidget;

/**
 * The inner geometry of a text area widget.
 * <p>
 * {@code MultiLineEditBoxMixin} needs these to draw a translated book page at the
 * same position as the original text. They are declared here (the class that
 * declares them) rather than shadowed from the subclass: a {@code @Shadow} method
 * has to exist in the target class itself, and {@code MultiLineEditBox} only
 * inherits them.
 */
@Mixin(AbstractTextAreaWidget.class)
public interface TextAreaWidgetAccessor {
	@Invoker("totalInnerPadding")
	int aiTranslate$totalInnerPadding();

	@Invoker("getInnerLeft")
	int aiTranslate$getInnerLeft();

	@Invoker("getInnerTop")
	int aiTranslate$getInnerTop();
}
