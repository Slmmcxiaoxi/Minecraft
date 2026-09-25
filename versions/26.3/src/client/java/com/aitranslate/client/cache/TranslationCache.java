package com.aitranslate.client.cache;

import java.util.Optional;

/** Translation store contract (in-memory index plus a disk backend). */
public interface TranslationCache {
	Optional<String> get(String original, String sourceLang, String targetLang);

	void put(String original, String translated, String sourceLang, String targetLang, String textType);

	void save();

	void load();

	void clear();

	int size();
}
