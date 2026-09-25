package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.network.chat.Component;

/**
 * The advancement toast (the box that slides in when the player earns one).
 * <p>
 * Unlike the advancement screen this is rendered per frame from the display info,
 * so redirecting the getter inside {@code extractRenderState} is enough and the
 * toast always shows the current translation - including one that only arrives
 * while the toast is already on screen.
 */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {

	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/advancements/DisplayInfo;getTitle()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$toastTitle(DisplayInfo info) {
		Component original = info.getTitle();
		Component translated = TranslationSupport.translated(original, TextType.ADVANCEMENT);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"advancement:" + original.getString() + "->" + translated.getString(),
					"[hook] ADVANCEMENT '{}' -> '{}'", original.getString(), translated.getString());
		}
		return translated;
	}
}
