package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

/**
 * Text display entity translation ({@code /summon minecraft:text_display}).
 * <p>
 * The client splits the display text into lines once and caches the result in
 * {@code clientDisplayCache}. Two hooks are needed for that:
 * <ul>
 *   <li>the component handed to the line splitter is replaced by its translation,
 *       so the cached lines are the translated ones,</li>
 *   <li>the cache is dropped whenever the render generation changes (a new
 *       translation arrived), so the lines are rebuilt with the translation - this
 *       is what makes the first render after the translation show the translation
 *       instead of requiring the entity to be re-summoned.</li>
 * </ul>
 * Only the render cache is touched; the entity's {@code text} data field, its
 * billboard settings and everything else stay untouched.
 */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayMixin {
	@Shadow
	private Display.TextDisplay.CachedInfo clientDisplayCache;

	@Unique
	private long aiTranslate$generation = Long.MIN_VALUE;

	@Inject(method = "cacheDisplay", at = @At("HEAD"))
	private void aiTranslate$invalidateCache(Display.TextDisplay.LineSplitter splitter,
			CallbackInfoReturnable<Display.TextDisplay.CachedInfo> info) {
		long generation = TranslationSupport.generation();
		if (aiTranslate$generation != generation) {
			aiTranslate$generation = generation;
			this.clientDisplayCache = null;
			if (com.aitranslate.client.AITranslateModClient.config != null
					&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
				com.aitranslate.client.util.DebugLog.once("invalidate:textdisplay",
						"[cache] text display lines invalidated (generation {})", generation);
			}
		}
	}

	@Redirect(method = "cacheDisplay", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Display$TextDisplay$TextRenderState;text()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$displayText(Display.TextDisplay.TextRenderState state) {
		Component original = state.text();
		var player = net.minecraft.client.Minecraft.getInstance().player;
		double distance = player == null ? Double.NaN
				: Math.sqrt(player.distanceToSqr((Display.TextDisplay) (Object) this));
		if (!com.aitranslate.client.focus.FocusTracker.withinTranslationRange(
				TextType.TEXT_DISPLAY, original.getString(), distance)) {
			return original;
		}
		var priority = com.aitranslate.client.focus.FocusTracker
				.priorityForDistance(TextType.TEXT_DISPLAY, distance);
		return TranslationSupport.translated(original, TextType.TEXT_DISPLAY, priority);
	}
}
