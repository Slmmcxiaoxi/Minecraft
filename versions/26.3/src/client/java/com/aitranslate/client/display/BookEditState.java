package com.aitranslate.client.display;

import net.minecraft.client.gui.components.MultiLineEditBox;

/**
 * Implemented by the book-and-quill edit screen (see
 * {@code BookEditScreenMixin}) so the edit box knows whether the page it is
 * showing is still the untouched page from the book or already being edited.
 */
public interface BookEditState {
	/** True while {@code box} still shows exactly the stored page text. */
	boolean aiTranslate$isPageUnmodified(MultiLineEditBox box);
}
