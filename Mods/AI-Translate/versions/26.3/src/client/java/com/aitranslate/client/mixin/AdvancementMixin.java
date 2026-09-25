package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.network.chat.Component;

/**
 * Advancement titles and descriptions in the advancement screen (fourth feedback
 * round).
 * <p>
 * {@code AdvancementWidget} splits the title and the description into lines in its
 * <strong>constructor</strong> and keeps them in final fields, so translating the
 * draw call would be too late and the fields cannot be replaced. The display info
 * getters are therefore redirected where the lines are built: the split (and with
 * it the box width and the tooltip layout) is then computed from the translation.
 * <p>
 * A translation that arrives later is handled by
 * {@code RefreshCoordinator}, which rebuilds the advancement screen's widgets (at
 * most once every 1.5 s) so the widgets are constructed again with the translation
 * in place. The advancement data itself is never touched.
 */
@Mixin(AdvancementWidget.class)
public abstract class AdvancementMixin {

	@Redirect(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/advancements/DisplayInfo;getTitle()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$advancementTitle(DisplayInfo info) {
		return TranslationSupport.translated(info.title(), TextType.ADVANCEMENT);
	}

	@Redirect(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/advancements/DisplayInfo;getDescription()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$advancementDescription(DisplayInfo info) {
		return TranslationSupport.translated(info.description(), TextType.ADVANCEMENT);
	}
}
