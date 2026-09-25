package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;

/**
 * Boss bar translation ({@code /bossbar ... set name}).
 * <p>
 * {@code BossHealthOverlay#extractRenderState} reads the bar title from the
 * client side boss event, measures it for centering and hands it to the
 * render-state extractor. Redirecting that getter replaces only the rendered
 * title - the event object (and with it the server state) stays untouched, and
 * the centering is computed from the translated text as well.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossBarMixin {

	@Redirect(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$bossBarName(LerpingBossEvent event) {
		Component original = event.getName();
		com.aitranslate.client.util.DebugLog.onceLazy("trace:bossbar:extract:" + original.getString(), () ->
				"[trace:bossbar:1-hook] entered ComponentExtractor source='" + original.getString() + "' tree="
						+ com.aitranslate.client.util.ComponentDiagnostics.describe(original) + " extracted="
						+ com.aitranslate.client.capture.ComponentExtractor.uniqueTexts(original));
		Component result = TranslationSupport.translated(original, TextType.BOSS_BAR, null, "diagnostic:bossbar");
		com.aitranslate.client.util.DebugLog.once("trace:bossbar:render:" + original.getString() + "->" + result.getString(),
				"[trace:bossbar:8-render] rendering={} source='{}' result='{}'",
				result == original ? "original" : "translation", original.getString(), result.getString());
		return result;
	}
}
