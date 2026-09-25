package com.aitranslate.client.dictionary;

/** One user-authored fixed translation. */
public record DictionaryEntry(String original, String translated, String sourceLang, String targetLang) {
	public DictionaryEntry normalized() {
		return new DictionaryEntry(clean(original), clean(translated), language(sourceLang, "auto"),
				language(targetLang, "zh_cn"));
	}

	public boolean valid() {
		return original != null && !original.isBlank() && translated != null && !translated.isBlank();
	}

	private static String clean(String value) {
		return value == null ? "" : value.trim();
	}

	private static String language(String value, String fallback) {
		return value == null || value.isBlank() ? fallback
				: value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
	}
}
