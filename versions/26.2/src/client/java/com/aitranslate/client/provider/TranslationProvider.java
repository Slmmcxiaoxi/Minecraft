package com.aitranslate.client.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A translation backend (OpenAI-compatible chat completion APIs). */
public interface TranslationProvider extends AutoCloseable {
	CompletableFuture<List<String>> translate(List<String> texts, String sourceLang, String targetLang);

	boolean supportsBatch();

	int maxBatchSize();

	/** Human readable provider name for the config screen. */
	default String name() {
		return getClass().getSimpleName();
	}

	/** Releases optional backend worker threads. */
	@Override
	default void close() {
	}
}
