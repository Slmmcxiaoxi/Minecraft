package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.BookEditState;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * Book-and-quill translation (fourth feedback round, revised in the tenth).
 * <p>
 * Tenth feedback round: the edit screen no longer translates the page it is
 * editing. The player is writing there - a translated page would lay out the
 * caret and the selection from a string that is not the one being typed, and it
 * would hide the player's own words. The requirement is explicit: "编辑模式下
 * 不翻译当前正在编辑的页面". Reading a book is unaffected: a signed (written)
 * book is opened in {@code BookViewScreen} and translated page by page.
 * <p>
 * The behaviour of the fourth round (translate an untouched page so the player
 * can read what they wrote in another language, switch back to the original as
 * soon as they type a character) is still available through the
 * {@code translateBookEditPage} option.
 * <p>
 * Nothing is ever written back into the book: the page list, the item data and
 * what signing would save all stay exactly as typed.
 */
@Mixin(MultiLineEditBox.class)
public abstract class MultiLineEditBoxMixin {

	@Shadow
	private Font font;

	@Shadow
	private int textColor;

	@Shadow
	private boolean textShadow;

	@Inject(method = "extractContents", at = @At("HEAD"), cancellable = true)
	private void aiTranslate$drawTranslatedPage(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
			float partialTick, CallbackInfo info) {
		if (!TranslationSupport.isActive(TextType.BOOK)) {
			return;
		}
		// Tenth feedback round: the editor is where the player *writes*, so by
		// default nothing is translated here at all - a translated page would move
		// the caret and hide what is being typed. Books that are only being read
		// (signed books) go through BookScreenMixin and are translated page by page.
		// The old reading-assist behaviour is still available as an option.
		if (com.aitranslate.client.AITranslateModClient.config == null
				|| !com.aitranslate.client.AITranslateModClient.config.translateBookEditPage
						&& !com.aitranslate.client.display.ScreenOriginalMode.forcesTranslation(TextType.BOOK)) {
			return;
		}
		if (!(Minecraft.getInstance().gui.screen() instanceof BookEditState state)) {
			return;
		}
		MultiLineEditBox box = (MultiLineEditBox) (Object) this;
		String original = box.getValue();
		if (original == null || original.isBlank() || !state.aiTranslate$isPageUnmodified(box)) {
			return;
		}
		String translated = TranslationSupport.translatedText(original, TextType.BOOK);
		if (translated.equals(original)) {
			// Nothing cached yet: the vanilla drawing shows the original page.
			return;
		}
		// Geometry comes from the accessor: those helpers live in the superclass, and a
		// @Shadow method has to be declared by the target class itself.
		TextAreaWidgetAccessor geometry = (TextAreaWidgetAccessor) (Object) this;
		int maxWidth = Math.max(20, box.getWidth() - geometry.aiTranslate$totalInnerPadding());
		extractor.textWithWordWrap(this.font, Component.literal(translated), geometry.aiTranslate$getInnerLeft(),
				geometry.aiTranslate$getInnerTop(), maxWidth, this.textColor, this.textShadow);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"bookedit:" + original + "->" + translated,
					"[hook] BOOK_EDIT page ({} chars) -> ({} chars)", original.length(), translated.length());
		}
		info.cancel();
	}
}
