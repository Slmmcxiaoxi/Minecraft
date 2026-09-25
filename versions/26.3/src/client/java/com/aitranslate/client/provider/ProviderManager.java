package com.aitranslate.client.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.aitranslate.client.config.ModConfig;

/** Creates and caches the provider instance described by the config. */
public class ProviderManager {
	private final ModConfig config;
	private volatile TranslationProvider provider;
	private volatile String providerConfigFingerprint;

	public ProviderManager(ModConfig config) {
		this.config = config;
	}

	public synchronized TranslationProvider provider() {
		String fingerprint = fingerprint();
		TranslationProvider current = provider;
		if (current == null || !fingerprint.equals(providerConfigFingerprint)) {
			if (current != null) {
				current.close();
			}
			current = new OpenAIProvider(config);
			provider = current;
			providerConfigFingerprint = fingerprint;
		}
		return current;
	}

	/** Releases provider workers, used by short-lived connection tests. */
	public synchronized void close() {
		TranslationProvider current = provider;
		provider = null;
		providerConfigFingerprint = null;
		if (current != null) {
			current.close();
		}
	}

	private String fingerprint() {
		return String.join("\u0000",
				String.valueOf(config.apiBaseUrl),
				String.valueOf(config.model),
				String.valueOf(config.apiKey),
				String.valueOf(config.requestTimeoutSeconds),
				String.valueOf(config.maxRetries),
				String.valueOf(config.httpThreads));
	}

	public boolean isConfigured() {
		return config.isApiConfigured();
	}

	/** Sends a tiny request so the user can validate the settings from the GUI. */
	public CompletableFuture<String> testConnection() {
		if (!isConfigured()) {
			return CompletableFuture.completedFuture("未配置 API Key / Base URL");
		}
		long started = System.currentTimeMillis();
		return provider().translate(List.of("Hello, adventurer!"), config.sourceLang, config.targetLang)
				.thenApply(result -> "连接成功 (" + (System.currentTimeMillis() - started) + " ms): " + result)
				.exceptionally(error -> "连接失败: " + rootMessage(error));
	}

	private static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}
}
