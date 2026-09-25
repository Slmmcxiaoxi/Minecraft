package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;

/**
 * Makes the book page cache follow the mod's render generation.
 * <p>
 * {@code BookViewScreen#visitText} only rebuilt its split page lines when the page
 * number changed, so a book that was opened before the translation arrived kept
 * showing the original page until the player flipped a page.
 * <p>
 * Resetting {@code cachedPage} to a value that never equals {@code currentPage}
 * makes the vanilla code rebuild the page (and therefore run it through the
 * translation hook) on the next frame.
 */
@Mixin(BookViewScreen.class)
public abstract class BookScreenCacheMixin {
	@Shadow
	private int cachedPage;

	@Shadow
	private int currentPage;

	@Shadow
	private BookViewScreen.BookAccess bookAccess;

	@Unique
	private long aiTranslate$generation = Long.MIN_VALUE;

	@Inject(method = "visitText", at = @At("HEAD"))
	private void aiTranslate$invalidatePageCache(ActiveTextCollector collector, boolean isBookmarked,
			CallbackInfo info) {
		// Eleventh feedback round: publish which page is being rendered, so the
		// redirect in BookScreenMixin can name it in the log.
		com.aitranslate.client.display.BookPageContext.set(this.currentPage,
				this.bookAccess == null ? -1 : this.bookAccess.getPageCount());
		long generation = TranslationSupport.generation();
		if (aiTranslate$generation != generation) {
			aiTranslate$generation = generation;
			this.cachedPage = -1;
			if (com.aitranslate.client.AITranslateModClient.config != null
					&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
				com.aitranslate.client.util.DebugLog.once("invalidate:book",
						"[cache] book page invalidated (generation {})", generation);
			}
		}
	}

}
