package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.display.BookEditState;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;

import java.util.List;

/**
 * State of the book-and-quill editor for {@link MultiLineEditBoxMixin}.
 * <p>
 * A book and quill is only ever read through its edit screen, so the page is
 * translated there - but only while the page was <em>not</em> modified since the
 * screen was opened. As soon as the player types, the view switches back to the
 * original text, because the caret and the selection are laid out from the
 * original string (a translated page would put the caret in the wrong place) and
 * because the player has to see what they are actually writing. Once the book is
 * signed it becomes a written book and is translated by {@code BookScreenMixin}
 * like any other book.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin implements BookEditState {

	@Shadow
	private int currentPage;

	@Shadow
	private List<String> pages;

	@Shadow
	private MultiLineEditBox page;

	@Override
	public boolean aiTranslate$isPageUnmodified(MultiLineEditBox box) {
		if (box == null || box != this.page || this.pages == null) {
			return false;
		}
		if (this.currentPage < 0 || this.currentPage >= this.pages.size()) {
			return false;
		}
		String edited = box.getValue();
		return edited != null && edited.equals(this.pages.get(this.currentPage));
	}

}
