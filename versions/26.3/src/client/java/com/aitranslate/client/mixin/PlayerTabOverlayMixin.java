package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * The player list (Tab) - fifth feedback round: custom display names and team
 * prefixes/suffixes there were not translated.
 * <p>
 * {@code getNameForDisplay} is the single funnel for a tab entry's text: it takes
 * the custom display name (or the profile name) and wraps it with the team
 * decoration. Replacing the returned component therefore covers the custom name
 * <em>and</em> the prefix/suffix around it in one place. The real player name is
 * skipped by {@code TextDetector} as everywhere else.
 * <p>
 * The header and footer are fields the server sets; they are translated at the draw
 * read, so a translation arriving later shows up without the server resending them.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

	@Shadow
	private Component header;

	@Shadow
	private Component footer;

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void aiTranslate$tabEntry(PlayerInfo info, CallbackInfoReturnable<Component> callback) {
		Component original = callback.getReturnValue();
		if (original == null) {
			return;
		}
		// This hook already has the authoritative profile. Protect it immediately so
		// the same frame cannot translate the name even if the periodic PlayerList
		// snapshot has not run yet.
		if (info != null && com.aitranslate.client.AITranslateModClient.scheduler != null) {
			com.aitranslate.client.AITranslateModClient.scheduler.detector()
					.protectPlayerName(info.getProfile().name());
		}
		Component translated = TranslationSupport.translated(original, TextType.PLAYER_LIST);
		if (translated != original) {
			if (com.aitranslate.client.AITranslateModClient.config != null
					&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
				com.aitranslate.client.util.DebugLog.once(
						"tab:" + original.getString() + "->" + translated.getString(),
						"[hook] PLAYER_LIST '{}' -> '{}'", original.getString(), translated.getString());
			}
			callback.setReturnValue(translated);
		}
	}

	@Redirect(method = "extractRenderState", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;header:Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$tabHeader(PlayerTabOverlay overlay) {
		return TranslationSupport.translated(this.header, TextType.PLAYER_LIST);
	}

	@Redirect(method = "extractRenderState", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;footer:Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$tabFooter(PlayerTabOverlay overlay) {
		return TranslationSupport.translated(this.footer, TextType.PLAYER_LIST);
	}
}
