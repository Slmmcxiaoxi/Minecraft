package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Makes the sign render cache follow the mod's render generation.
 * <p>
 * {@code SignText#getRenderMessages} builds the four rendered lines once and
 * caches them in {@code renderMessages}. Because of that cache the translation
 * arrived too late: the lines were already built from the original text, and the
 * sign only showed the translation after it was edited (which creates a new
 * {@code SignText} and thereby drops the cache).
 * <p>
 * This mixin clears that cache whenever the generation changes - for example when
 * a new translation came back - so the next frame rebuilds the lines through the
 * translation hook. Nothing of the sign's data is modified; only a derived render
 * cache is recomputed.
 */
@Mixin(SignText.class)
public abstract class SignTextCacheMixin {
	@Shadow
	private FormattedCharSequence[] renderMessages;

	@Unique
	private long aiTranslate$generation = Long.MIN_VALUE;

	@Inject(method = "getRenderMessages(ZLjava/util/function/Function;)[Lnet/minecraft/util/FormattedCharSequence;",
			at = @At("HEAD"))
	private void aiTranslate$invalidateRenderCache(boolean filtered,
			java.util.function.Function<net.minecraft.network.chat.Component, FormattedCharSequence> mapper,
			CallbackInfoReturnable<FormattedCharSequence[]> info) {
		long generation = TranslationSupport.generation();
		if (aiTranslate$generation != generation) {
			aiTranslate$generation = generation;
			this.renderMessages = null;
			if (com.aitranslate.client.AITranslateModClient.config != null
					&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
				com.aitranslate.client.util.DebugLog.once("invalidate:sign",
						"[cache] sign render lines invalidated (generation {})", generation);
			}
		}
	}
}
