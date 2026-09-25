package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;

import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

/** Translates only the structured death-cause line on the death screen. */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin {
	@ModifyArg(method = "visitText", at = @At(value = "INVOKE", ordinal = 1,
			target = "Lnet/minecraft/client/gui/ActiveTextCollector;accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/network/chat/Component;)V"), index = 3)
	private Component aiTranslate$deathCause(Component original) {
		Component translated = TranslationSupport.translated(original, TextType.CHAT);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"death-screen:" + original.getString() + "->" + translated.getString(),
					"[AT] Death screen rendering={} result='{}'",
					translated == original ? "original/pending" : "translation", translated.getString());
		}
		return translated;
	}
}
