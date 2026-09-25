package com.aitranslate.client.display;

import com.aitranslate.client.capture.TextType;

/**
 * Display mode of a translated text.
 * <p>
 * The MVP shipped five modes (bilingual stacked / inline / hover / click toggling
 * / translation only). Play testing showed that keeping the original and the
 * translation in the same spot does not work well in the game's UI - the space
 * is too tight and the two texts are hard to align.
 * <p>
 * The mod therefore settled on a single mode: the translation <em>replaces</em>
 * the original at exactly the same place, inheriting position, size, lighting,
 * orientation, style and events. The original stays reachable through the master
 * switch (on = translation, off = original) - nothing in the game data is ever
 * changed, so switching is instant.
 * <p>
 * The enum is kept as a single constant so caches, logs and possible future
 * modes stay expressible.
 */
public enum DisplayMode {
	/** The translation covers the original text at exactly the same render position. */
	TRANSLATION_ONLY("ai_translate.display.only");

	private final String translationKey;

	DisplayMode(String translationKey) {
		this.translationKey = translationKey;
	}

	public String translationKey() {
		return translationKey;
	}

	public static DisplayMode defaultFor(TextType type) {
		return TRANSLATION_ONLY;
	}
}
