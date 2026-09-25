package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.network.chat.Component;

/**
 * Titles of the advancement screen's category tabs (fifth feedback round).
 * <p>
 * The tab keeps its own {@code title} field, filled from the tab's root
 * advancement at construction; the screen reads it every frame through
 * {@code getTitle()}. Translating the getter at the draw site means a translation
 * that arrives later shows up without rebuilding the screen (and the tab title is
 * the only text of the screen that does not pass through one of the other hooks).
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementScreenMixin {

	@Redirect(method = "extractWindow", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementTab;getTitle()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$tabTitle(AdvancementTab tab) {
		return aiTranslate$title(tab.getTitle());
	}

	@Redirect(method = "extractTooltips", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementTab;getTitle()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$tabTooltipTitle(AdvancementTab tab) {
		return aiTranslate$title(tab.getTitle());
	}

	private static Component aiTranslate$title(Component original) {
		Component translated = TranslationSupport.translated(original, TextType.ADVANCEMENT);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"advancementTab:" + original.getString() + "->" + translated.getString(),
					"[hook] ADVANCEMENT_TAB '{}' -> '{}'", original.getString(), translated.getString());
		}
		return translated;
	}

}
