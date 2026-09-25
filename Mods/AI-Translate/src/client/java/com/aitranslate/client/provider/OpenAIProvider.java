package com.aitranslate.client.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OpenAI-compatible chat completion provider.
 * <p>
 * Texts are sent as a JSON array in a single user message and the model is
 * asked to answer with a JSON array of the same length, which keeps the number
 * of HTTP round trips low for chat-heavy RPG maps.
 */
public class OpenAIProvider implements TranslationProvider {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	private static final String SYSTEM_PROMPT = """
			You are a professional translator working on a Minecraft RPG adventure map.
			You translate hardcoded in-game text (chat, dialogue, signs, books, titles, boss bars, scoreboards, dialogs) into the target language.
			Rules:
			1. Answer with a JSON array of strings ONLY. No prose, no markdown, no code fences.
			2. The array must have exactly the same number of elements, in the same order, as the input array.
			   Every element must contain only the translated visible text. Never put a JSON object, serialized Minecraft Component, markdown fence, label or explanation inside an element.
			   Do not add surrounding quotation marks, brackets, "Translation:" labels, END markers, timestamps, log lines or metadata to an element.
			3. Keep real placeholders such as %s, %1$s, {0}, {1:%.2f}, ${name} byte for byte unchanged. Words wrapped in symbols, such as {water} or <sheep>, are ordinary text and must be translated while preserving the symbols.
			4. Keep Minecraft formatting codes (e.g. section sign codes) and numbers, coordinates and item ids unchanged.
			5. Never translate player names, server addresses or command syntax.
			6. Keep the tone fitting the map: narrative, cinematic, natural.
			7. If an element is already in the target language, copy it unchanged.
			""";

	/**
	 * The fallback protocol for a single text (eleventh feedback round).
	 * <p>
	 * A local 1.8B translation model is very good at translating and rather bad at
	 * emitting valid JSON: it sometimes appends the fields of the request it was
	 * given, which makes the whole answer unreadable. Asking for the translation
	 * and nothing else removes the JSON step entirely, and with one text in the
	 * request there is nothing that could be mapped to the wrong element.
	 */
	private static final String PLAIN_PROMPT = """
			You are a professional translator working on a Minecraft RPG adventure map.
			Translate the text the user sends into the target language.
			Rules:
			1. Answer with the translation only. No JSON, no array, no quotation marks around the whole answer, no notes, no explanations.
			   Never return a serialized Minecraft Component such as {"text":"translation"}.
			   Do not add brackets, "Translation:" labels, END markers, timestamps, log lines or metadata.
			2. Keep the line breaks of the input.
			3. Keep real placeholders such as %s, %1$s, {0}, {1:%.2f}, ${name} byte for byte unchanged. Words wrapped in symbols, such as {water} or <sheep>, are ordinary text and must be translated while preserving the symbols.
			4. Keep Minecraft formatting codes and numbers, coordinates and item ids unchanged.
			5. Never translate player names, server addresses or command syntax.
			6. Keep the tone fitting the map: narrative, cinematic, natural.
			""";

	private final ModConfig config;
	private final HttpClient http;
	private final ExecutorService httpExecutor;
	private final ScheduledExecutorService scheduler;

	public OpenAIProvider(ModConfig config) {
		this.config = config;
		this.httpExecutor = Executors.newFixedThreadPool(Math.max(1, Math.min(8, config.httpThreads)), r -> {
			Thread thread = new Thread(r, "ai-translate-http");
			thread.setDaemon(true);
			return thread;
		});
		this.http = HttpClient.newBuilder()
				// HTTP/1.1 on purpose (eleventh feedback round). Java's HttpClient
				// negotiates HTTP/2 by default and sends an "Upgrade: h2c" header with
				// every request; llama.cpp's server (the local Hy-MT2 setup the book bug
				// was found with) does not answer that upgrade, and the request then
				// hangs until the timeout expires without the server ever being asked
				// to translate anything. Measured with the exact same request body:
				// default (h2c) -> timeout after 20 s, HTTP/1.1 -> HTTP 200 in 314 ms.
				// Every OpenAI-compatible endpoint speaks HTTP/1.1, so nothing is lost.
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(Duration.ofSeconds(Math.min(20, Math.max(5, config.requestTimeoutSeconds))))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.executor(httpExecutor)
				.build();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "ai-translate-retry");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public void close() {
		scheduler.shutdownNow();
		httpExecutor.shutdownNow();
	}

	@Override
	public String name() {
		return "OpenAI compatible (" + config.model + ")";
	}

	@Override
	public boolean supportsBatch() {
		return true;
	}

	@Override
	public int maxBatchSize() {
		return Math.max(1, config.maxBatchSize);
	}

	@Override
	public CompletableFuture<List<String>> translate(List<String> texts, String sourceLang, String targetLang) {
		if (texts.isEmpty()) {
			return CompletableFuture.completedFuture(List.of());
		}
		if (config.apiKey == null || config.apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new TranslationTransportException(
					"未配置 API Key", true, false, null));
		}
		return attempt(texts, sourceLang, targetLang, 0, null, payloadChars(texts), false);
	}

	/** Total characters of one request - the size that drives the timeout. */
	private static int payloadChars(List<String> texts) {
		int chars = 0;
		for (String text : texts) {
			chars += text == null ? 0 : text.length();
		}
		return chars;
	}

	/**
	 * The per-request timeout (tenth feedback round).
	 * <p>
	 * A fixed 30 s timeout is a guess that is wrong in both directions: much too
	 * long for a single short sign line, and much too short for a batch of long
	 * book paragraphs, where a local model needs to generate thousands of tokens
	 * before it answers at all. The timeout therefore follows the payload, with
	 * the configured value as the floor (it already covers the first
	 * {@value #TIMEOUT_FREE_CHARS} characters of a request) and
	 * {@code longRequestTimeoutSeconds} as the ceiling. A request that runs into it
	 * is reported as "still processing", not as "the network is down".
	 * <p>
	 * Package private for tests.
	 */
	static final int TIMEOUT_FREE_CHARS = 100;

	int timeoutSecondsFor(int chars, int attempt) {
		int base = Math.max(5, config.requestTimeoutSeconds);
		int ceiling = Math.max(base, config.longRequestTimeoutSeconds);
		// Rough model: a local 7B translation model produces roughly 10 characters
		// of Chinese per second, so every ten characters of payload beyond the free
		// allowance buys one more second. A retry gets another 30 s, because a
		// timeout is as likely to mean "still generating" as "broken".
		int extra = Math.max(0, chars - TIMEOUT_FREE_CHARS) / 10;
		int estimated = base + extra + attempt * 30;
		return Math.max(base, Math.min(ceiling, estimated));
	}

	private CompletableFuture<List<String>> attempt(List<String> texts, String sourceLang, String targetLang,
			int attempt, String hint, int chars, boolean plain) {
		HttpRequest request;
		try {
			request = buildRequest(texts, sourceLang, targetLang, hint, timeoutSecondsFor(chars, attempt), plain);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}

		final boolean plainMode = plain;
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> {
					if (response.statusCode() >= 400) {
						throw new CompletionException(httpError(response));
					}
					return parseResponse(response.body(), texts, targetLang, plainMode);
				})
				.handle((result, error) -> {
					if (error == null) {
						return CompletableFuture.completedFuture(result);
					}
					Throwable failure = classify(unwrap(error));
					if (attempt >= Math.max(0, config.maxRetries)) {
						return CompletableFuture.<List<String>>failedFuture(failure);
					}
					if (failure instanceof TranslationTransportException transport && transport.permanent()) {
						// A wrong key or an unknown model does not get better by trying
						// again; failing fast keeps the queue moving.
						return CompletableFuture.<List<String>>failedFuture(failure);
					}
					long delay = Math.min(8000L, 400L * (1L << Math.min(attempt, 4)));
					LOGGER.warn("Translation attempt {} failed ({}), retrying in {} ms", attempt + 1,
							failure.getMessage(), delay);
					// A malformed answer (wrong number of elements, broken JSON) is the
					// one error the model can fix when told what was wrong, so the retry
					// carries an explicit correction. Measured on a local 7B translation
					// model: the wrong count and "not a JSON array" failures disappear
					// once the model is told the expected count.
					//
					// For a single text the protocol itself is switched as well
					// (eleventh feedback round): a model that cannot produce a one
					// element JSON array usually produces the bare translation without
					// any trouble, and with one text there is no order to get wrong.
					boolean nextPlain = nextPlainMode(failure, plainMode, texts.size());
					String nextHint = retryHint(failure, texts.size(), hint, nextPlain);
					CompletableFuture<List<String>> next = new CompletableFuture<>();
					scheduler.schedule(
							() -> attempt(texts, sourceLang, targetLang, attempt + 1, nextHint, chars, nextPlain)
									.whenComplete((value, retryError) -> {
										if (retryError != null) {
											next.completeExceptionally(unwrap(retryError));
										} else {
											next.complete(value);
										}
									}),
							delay, TimeUnit.MILLISECONDS);
					return next;
				})
				.thenCompose(future -> future);
	}

	/**
	 * Maps a raw failure onto the two kinds the scheduler cares about
	 * (tenth feedback round): a transport problem (timeout / no route / server
	 * error) versus an unusable model answer.
	 * <p>
	 * Package private for tests.
	 */
	static Throwable classify(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root && !(root instanceof ModelAnswerException)
				&& !(root instanceof TranslationTransportException)) {
			root = root.getCause();
		}
		if (root instanceof ModelAnswerException || root instanceof TranslationTransportException) {
			return root;
		}
		String message = root.getMessage() == null ? "" : root.getMessage();
		String named = message.isBlank() ? root.getClass().getSimpleName() : message;
		if (root instanceof java.net.http.HttpTimeoutException || root instanceof java.net.SocketTimeoutException) {
			return new TranslationTransportException("请求超时（服务器仍在处理）: " + named, false, true, root);
		}
		if (root instanceof java.net.ConnectException || root instanceof java.net.NoRouteToHostException
				|| root instanceof java.net.UnknownHostException || root instanceof java.net.SocketException
				|| root instanceof javax.net.ssl.SSLException || root instanceof java.io.IOException) {
			return new TranslationTransportException("无法连接 API: " + named, false, false, root);
		}
		if (message.startsWith("Expected ") || message.startsWith("Model did not return")
				|| message.startsWith("Unexpected response") || message.startsWith("Response has no choices")
				|| message.startsWith("Answer truncated") || message.startsWith("Answer echoes")) {
			return new ModelAnswerException(message, root);
		}
		return new TranslationTransportException(named, false, false, root);
	}

	/** HTTP status → transport error, with the statuses that never recover marked permanent. */
	private static TranslationTransportException httpError(HttpResponse<String> response) {
		int status = response.statusCode();
		String detail = "HTTP " + status + ": " + abbreviate(response.body());
		boolean permanent = status == 400 || status == 401 || status == 403 || status == 404
				|| status == 405 || status == 422;
		if (permanent) {
			return new TranslationTransportException(detail + "（请检查 API Key / 模型名 / Base URL）", true, false, null);
		}
		if (status == 408 || status == 504) {
			return new TranslationTransportException(detail + "（服务器仍在处理）", false, true, null);
		}
		return new TranslationTransportException(detail, false, false, null);
	}

	private static Throwable unwrap(Throwable error) {
		if (error instanceof CompletionException && error.getCause() != null) {
			return error.getCause();
		}
		return error;
	}

	/**
	 * Message added to the retry request when the model answered with the wrong
	 * number of elements. Telling the model what was wrong is what makes the retry
	 * succeed; other errors (timeouts, HTTP errors) keep the previous hint, since
	 * repeating the instruction does not help there.
	 * <p>
	 * Package private for tests.
	 */
	static String retryHint(Throwable error, int expected, String currentHint) {
		return retryHint(error, expected, currentHint, false);
	}

	/**
	 * Same, but aware of the protocol of the next attempt: a plain-text attempt
	 * must not be told to answer with a JSON array (eleventh feedback round).
	 */
	static String retryHint(Throwable error, int expected, String currentHint, boolean nextPlain) {
		String message = error == null ? null : error.getMessage();
		if (nextPlain) {
			return "Reply with the translation only - no JSON, no array, no quotation marks "
					+ "around the whole answer, no explanation.";
		}
		if (message != null && message.startsWith("Answer truncated")) {
			// The answer was cut off by the token limit: the only way out is a
			// shorter answer, not a different format.
			return "Your previous answer was cut off. Reply with the JSON array only, "
					+ "as short as possible, and do not repeat the input.";
		}
		if (message != null && (message.startsWith("Expected ") || error instanceof ModelAnswerException)) {
			// "Do not repeat the fields of the input" is there because of the
			// observed failure shape ["译文", "source_language": "auto", …], where
			// the model pasted the request envelope after its own answer.
			return "Your previous answer was rejected. Reply again with a JSON array of exactly " + expected
					+ " strings, one per input element, in the same order. "
					+ "Do not merge, split, add or drop elements, and do not repeat the fields of the input.";
		}
		if (error instanceof TranslationTransportException transport && transport.timeout()) {
			// The server was still generating: telling it to be brief helps more
			// than repeating the format instruction (tenth feedback round).
			return "Your previous answer took too long. Reply with the JSON array only, "
					+ "without any extra explanation, as short as possible.";
		}
		return currentHint;
	}

	/**
	 * Which protocol the next attempt uses.
	 * <p>
	 * A single text alternates between the JSON array protocol and the plain-text
	 * one whenever the model answered with something unreadable, because with one
	 * element the plain answer cannot be mapped to the wrong text. A batch stays on
	 * the JSON protocol (the order is what identifies the elements), and a transport
	 * failure never changes the protocol - that is a network problem, not a
	 * formatting one.
	 * <p>
	 * Package private for tests.
	 */
	static boolean nextPlainMode(Throwable failure, boolean plain, int textCount) {
		if (textCount != 1) {
			return plain;
		}
		return failure instanceof ModelAnswerException ? !plain : plain;
	}

	/**
	 * The token budget of one request (eleventh feedback round).
	 * <p>
	 * Without a limit a small model that falls into a repetition loop keeps
	 * generating until the server's own cap, which turns a broken answer into a
	 * request that looks like a network timeout. The budget is generous (roughly
	 * twice the payload plus room for the JSON envelope), so a healthy answer never
	 * hits it, and a runaway one is cut off within seconds.
	 * <p>
	 * Package private for tests.
	 */
	static int maxTokensFor(List<String> texts) {
		int chars = payloadChars(texts);
		int budget = chars + 128 + 64 * Math.max(1, texts.size());
		return Math.max(256, Math.min(4096, budget));
	}

	private HttpRequest buildRequest(List<String> texts, String sourceLang, String targetLang, String hint,
			int timeoutSeconds, boolean plain) {
		String target = languageLabel(targetLang);
		JsonObject userPayload = null;
		if (!plain) {
			JsonArray input = new JsonArray();
			texts.forEach(input::add);
			userPayload = new JsonObject();
			userPayload.add("texts", input);
			userPayload.addProperty("source_language", sourceLang == null ? "auto" : sourceLang);
			userPayload.addProperty("target_language", targetLang == null ? "zh_cn" : targetLang);
		}

		JsonObject system = new JsonObject();
		system.addProperty("role", "system");
		system.addProperty("content", (plain ? PLAIN_PROMPT : SYSTEM_PROMPT)
				+ "\nThe required target language is " + target + ". Do not use another language.");

		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.addProperty("content", plain ? texts.get(0) : userPayload.toString());

		JsonArray messages = new JsonArray();
		messages.add(system);
		messages.add(user);
		if (hint != null && !hint.isBlank()) {
			JsonObject correction = new JsonObject();
			correction.addProperty("role", "user");
			correction.addProperty("content", hint);
			messages.add(correction);
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", config.model);
		// Translation is a deterministic task: at temperature 0 the models we
		// measured (DeepSeek and a local 7B MT model) stop returning arrays with a
		// missing element or with prose around them.
		body.addProperty("temperature", 0);
		body.addProperty("stream", false);
		body.addProperty("max_tokens", maxTokensFor(texts));
		body.add("messages", messages);

		return HttpRequest.newBuilder(endpoint())
				.timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("Authorization", "Bearer " + config.apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
				.build();
	}

	private URI endpoint() {
		String base = config.apiBaseUrl == null || config.apiBaseUrl.isBlank()
				? "https://api.deepseek.com"
				: config.apiBaseUrl.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		if (base.endsWith("/chat/completions")) {
			return URI.create(base);
		}
		if (base.endsWith("/v1")) {
			return URI.create(base + "/chat/completions");
		}
		return URI.create(base + "/v1/chat/completions");
	}

	private List<String> parseResponse(String body, List<String> texts, String targetLang, boolean plain) {
		JsonElement root = JsonParser.parseString(body);
		if (!root.isJsonObject()) {
			throw new ModelAnswerException("Unexpected response: " + abbreviate(body));
		}
		JsonObject object = root.getAsJsonObject();
		if (!object.has("choices") || object.getAsJsonArray("choices").isEmpty()) {
			throw new ModelAnswerException("Response has no choices: " + abbreviate(body));
		}
		JsonObject choice = object.getAsJsonArray("choices").get(0).getAsJsonObject();
		String content = choice.has("message")
				? choice.getAsJsonObject("message").get("content").getAsString()
				: choice.get("text").getAsString();
		String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
				? choice.get("finish_reason").getAsString()
				: "";

		if (plain) {
			String translation = ModelAnswers.parsePlain(content, texts.get(0));
			if (translation == null) {
				logUnusableAnswer(texts, content, "plain answer unusable");
				throw new ModelAnswerException("Expected 1 translations but got 0");
			}
			if (com.aitranslate.client.scheduler.AnswerQuality.hasClearlyWrongScript(translation, targetLang)) {
				logUnusableAnswer(texts, content, "answer uses the wrong target-language script");
				throw new ModelAnswerException("Answer uses a script incompatible with target language "
						+ languageLabel(targetLang));
			}
			return List.of(translation);
		}

		try {
			List<String> translations = parseTranslations(content, texts.size());
			if (translations.stream().anyMatch(value ->
					com.aitranslate.client.scheduler.AnswerQuality.hasClearlyWrongScript(value, targetLang))) {
				logUnusableAnswer(texts, content, "answer uses the wrong target-language script");
				throw new ModelAnswerException("Answer uses a script incompatible with target language "
						+ languageLabel(targetLang));
			}
			return translations;
		} catch (ModelAnswerException e) {
			if ("length".equals(finishReason)) {
				// The answer was cut off by max_tokens: a different reason (and a
				// different retry hint) than a model that answered something else.
				logUnusableAnswer(texts, content, "answer truncated by the token limit");
				throw new ModelAnswerException("Answer truncated (token limit reached)", e);
			}
			logUnusableAnswer(texts, content, e.getMessage());
			throw e;
		}
	}

	/**
	 * Logs the raw answer of an unusable reply.
	 * <p>
	 * This is the diagnostic that was missing when the book page bug was reported:
	 * the log said "Expected 1 translations but got 0" without showing what the
	 * model had actually answered, so the shape of the broken answer
	 * ({@code ["译文", "source_language": "auto", …]}) was invisible.
	 */
	private static void logUnusableAnswer(List<String> texts, String content, String reason) {
		LOGGER.warn("Unusable model answer ({} text(s), input \"{}\"): {} | answer: {}", texts.size(),
				abbreviate(texts.isEmpty() ? "" : texts.get(0)), reason, abbreviate(content));
	}

	/**
	 * Turns the content of the model answer into exactly {@code expected} strings.
	 * <p>
	 * Strict JSON is tried first, then the tolerant reader in
	 * {@link ModelAnswers}, which covers the shapes
	 * a small model actually produces: an unterminated array, raw newlines inside a
	 * string, one extra nesting level, the input fields appended after the array.
	 * An array with a different number of elements is still rejected - the order
	 * carries the meaning, so a short answer must never be mapped onto the first
	 * elements by position.
	 * <p>
	 * Package private for tests.
	 */
	static List<String> parseTranslations(String content, int expected) {
		return ModelAnswers.parseArray(content, expected);
	}

	/** A code alone is ambiguous to small MT models, especially in plain mode. */
	static String languageLabel(String language) {
		String code = language == null ? "" : language.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
		return switch (code) {
			case "zh", "zh_cn", "zh_hans" -> "Simplified Chinese (简体中文, zh_cn)";
			case "zh_tw", "zh_hk", "zh_hant" -> "Traditional Chinese (繁體中文, zh_tw)";
			case "en", "en_us", "en_gb" -> "English (" + (code.isBlank() ? "en" : code) + ")";
			case "ko", "ko_kr" -> "Korean (한국어, ko_kr)";
			case "ja", "ja_jp" -> "Japanese (日本語, ja_jp)";
			case "de", "de_de", "de_at", "de_ch" -> "German (Deutsch, " + code + ")";
			case "fr", "fr_fr", "fr_ca" -> "French (Français, " + code + ")";
			case "es", "es_es", "es_mx" -> "Spanish (Español, " + code + ")";
			case "it", "it_it" -> "Italian (Italiano, " + code + ")";
			case "pt", "pt_br", "pt_pt" -> "Portuguese (Português, " + code + ")";
			case "ru", "ru_ru" -> "Russian (Русский, " + code + ")";
			case "uk", "uk_ua" -> "Ukrainian (Українська, " + code + ")";
			case "pl", "pl_pl" -> "Polish (Polski, " + code + ")";
			case "nl", "nl_nl", "nl_be" -> "Dutch (Nederlands, " + code + ")";
			case "tr", "tr_tr" -> "Turkish (Türkçe, " + code + ")";
			case "ar", "ar_sa" -> "Arabic (العربية, " + code + ")";
			case "hi", "hi_in" -> "Hindi (हिन्दी, " + code + ")";
			case "th", "th_th" -> "Thai (ไทย, " + code + ")";
			case "vi", "vi_vn" -> "Vietnamese (Tiếng Việt, " + code + ")";
			case "id", "id_id" -> "Indonesian (Bahasa Indonesia, " + code + ")";
			default -> code.isBlank() ? "Simplified Chinese (简体中文, zh_cn)" : code;
		};
	}

	private static String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		String flat = text.replaceAll("\\s+", " ").trim();
		return flat.length() <= 240 ? flat : flat.substring(0, 240) + "...";
	}
}
