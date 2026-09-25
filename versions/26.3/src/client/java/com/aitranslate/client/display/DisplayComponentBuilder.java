package com.aitranslate.client.display;

import java.util.Map;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.capture.ComponentExtractor;
import com.aitranslate.client.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Builds the component that covers the original text in the render layer.
 * <p>
 * The original component is only read: the result is a fresh component whose
 * literal leaves carry the translation while styles, click/hover events and
 * placeholders of the original are preserved. Because the replacement has the
 * same component shape, the renderer draws it at exactly the same position with
 * the same lighting, scale and orientation as the original.
 */
public final class DisplayComponentBuilder {
	private DisplayComponentBuilder() {
	}

	/** @see #translated(Component, Map, ModConfig) */
	public static Component translated(Component original, Map<String, String> translations) {
		return translated(original, translations, AITranslateModClient.config);
	}

	/**
	 * Replacement component for {@code original}.
	 *
	 * @param original     the component the game was about to render (never modified)
	 * @param translations literal text -> translation
	 */
	public static Component translated(Component original, Map<String, String> translations, ModConfig config) {
		if (original == null || translations == null || translations.isEmpty()) {
			return original;
		}
		MutableComponent translated = ComponentExtractor.rebuild(original, translations);
		applyTranslationStyle(translated, config);
		if (config != null && config.translationPrefix != null && !config.translationPrefix.isEmpty()) {
			MutableComponent prefixed = Component.literal(config.translationPrefix);
			if (config.translationColor != null && !config.translationColor.isBlank()) {
				TextColor color = parseColor(config.translationColor);
				if (color != null) {
					prefixed.setStyle(Style.EMPTY.withColor(color));
				}
			}
			return TranslationSupport.markDisplay(prefixed.append(translated));
		}
		return TranslationSupport.markDisplay(translated);
	}

	private static void applyTranslationStyle(MutableComponent component, ModConfig config) {
		if (config == null || config.translationColor == null || config.translationColor.isBlank()) {
			return;
		}
		TextColor color = parseColor(config.translationColor);
		if (color == null) {
			return;
		}
		component.setStyle(component.getStyle().withColor(color));
	}

	/** Accepts {@code #RRGGBB}, {@code RRGGBB} or a legacy {@code &a} style code. */
	public static TextColor parseColor(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.trim();
		if (value.startsWith("&") && value.length() == 2) {
			net.minecraft.ChatFormatting formatting = net.minecraft.ChatFormatting.getByCode(value.charAt(1));
			return formatting == null ? null : TextColor.fromLegacyFormat(formatting);
		}
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		try {
			return TextColor.fromRgb(Integer.parseInt(value, 16));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
