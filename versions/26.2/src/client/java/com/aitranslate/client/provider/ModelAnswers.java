package com.aitranslate.client.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the translation out of a model answer (eleventh feedback round).
 * <p>
 * The protocol asks for a JSON array of strings, and for a while anything else
 * was thrown away. That turned out to be the root cause of "one book page never
 * translates": the configured local model (Hy-MT2 1.8B behind llama.cpp)
 * sometimes answers with the <em>input envelope</em> still attached:
 *
 * <pre>
 * [["对其他来说，SAM 令人恐惧…被夺走的一切], "source_language": "auto", "target_language": "zh_cn"]
 * </pre>
 *
 * which is not valid JSON at all ({@code MalformedJsonException: Unterminated
 * array}), so strict parsing <em>and</em> the "cut everything between the first
 * [ and the last ]" fallback both failed and the text was reported as
 * "Expected 1 translations but got 0". The same model also answers with a
 * truncated array, with one extra nesting level, or with the array wrapped in an
 * object.
 * <p>
 * Everything here is a pure function on the answer text, which is why it lives
 * in its own class: the shapes above are all covered by unit tests.
 */
public final class ModelAnswers {
	private static final java.util.regex.Pattern LOG_LINE = java.util.regex.Pattern.compile(
			"(?im)^\\s*(?:\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}|\\[?\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?]?)\\s*(?:\\[(?:TRACE|DEBUG|INFO|WARN|ERROR)]|(?:TRACE|DEBUG|INFO|WARN|ERROR)\\b)");
	private static final java.util.regex.Pattern STACK_LINE = java.util.regex.Pattern.compile(
			"(?im)^\\s*(?:Caused by:|Exception in thread|at [A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+\\([^)]*\\))");
	private static final java.util.regex.Pattern INTERNAL_LOG = java.util.regex.Pattern.compile(
			"(?im)^\\s*\\[(?:ai[_ -]?translate|aitranslate)]\\s*(?:\\[(?:TRACE|DEBUG|INFO|WARN|ERROR)]|:)");

	private ModelAnswers() {
	}

	/**
	 * The translations of an answer, or a {@link ModelAnswerException}.
	 * <p>
	 * A wrong number of elements is still an error - the order carries the
	 * meaning, so a short answer must never be mapped onto the first elements by
	 * position. The message keeps the exact shape the scheduler and the retry
	 * logic match on ({@code "Expected N translations but got M"}).
	 */
	public static List<String> parseArray(String content, int expected) {
		String text = stripFences(content);

		List<String> strict = tryStrictArray(text);
		if (strict != null && strict.size() == expected) {
			return cleanList(strict, expected);
		}
		// A small translation model sometimes treats one phrase such as "red yellow"
		// as two independent output items: ["红色", "黄色"]. There was only one
		// input, so these are parts of that one translation, not two mappings. Keeping
		// them together avoids switching to the plain fallback solely because the
		// model segmented a phrase.
		if (expected == 1 && strict != null && strict.size() > 1
				&& strict.stream().noneMatch(String::isBlank)
				&& strict.stream().noneMatch(ModelAnswers::looksLikeEnvelopeField)) {
			return cleanList(List.of(String.join(" ", strict)), expected);
		}
		List<String> salvaged = salvageArray(text);
		if (salvaged.size() == expected) {
			return cleanList(salvaged, expected);
		}

		// {"translations": [...]} / {"texts": [...]} and friends.
		String inner = arrayInsideObject(text);
		if (inner != null) {
			List<String> nested = tryStrictArray(inner);
			if (nested != null && nested.size() == expected) {
				return cleanList(nested, expected);
			}
			List<String> nestedSalvaged = salvageArray(inner);
			if (nestedSalvaged.size() == expected) {
				return cleanList(nestedSalvaged, expected);
			}
		}

		int got = Math.max(strict == null ? 0 : strict.size(), salvaged.size());
		throw new ModelAnswerException("Expected " + expected + " translations but got " + got);
	}

	/**
	 * The translation of a plain-text (non-JSON) answer, or {@code null} when the
	 * answer cannot be used.
	 * <p>
	 * Used by the fallback protocol for single texts: asking a small model for a
	 * bare translation instead of a JSON array removes the one step it keeps
	 * getting wrong. Because the request asked for the translation <em>only</em>,
	 * the whole answer is the translation - but the obvious ways of answering
	 * something else (an empty answer, a repeated input envelope, an echo of the
	 * input) are rejected.
	 *
	 * @param original the text that was sent, used to detect an echo
	 */
	public static String parsePlain(String content, String original) {
		String text = stripFences(content);
		text = stripLabel(text);
		text = unwrapQuotes(text);
		// Some models wrap the single translation in an array even when asked not
		// to ("["译文"]") - take the element out and clean it up again.
		if (text.startsWith("[") && text.endsWith("]")) {
			List<String> salvaged = salvageArray(text);
			if (salvaged.size() == 1) {
				text = unwrapQuotes(stripLabel(salvaged.get(0).trim()));
			}
		}
		text = cleanTranslation(text);
		if (text.isBlank()) {
			return null;
		}
		if (looksLikeInputEnvelope(text)) {
			return null;
		}
		if (original != null && text.equals(original.trim())) {
			// The model copied the input instead of translating it.
			return null;
		}
		return text;
	}

	/**
	 * Removes answer protocol accidentally nested inside one translation element.
	 * For example {@code {"text":"注意"}} becomes {@code 注意}. Only recognised
	 * translation/component fields are unwrapped; arbitrary metadata is never
	 * concatenated into visible text.
	 */
	public static String cleanTranslation(String content) {
		String text = normalizeSurface(content);
		for (int pass = 0; pass < 4 && !text.isBlank(); pass++) {
			String unwrapped = unwrapTranslationEnvelope(text);
			if (unwrapped == null || unwrapped.equals(text)) {
				break;
			}
			text = normalizeSurface(unwrapped);
		}
		text = stripTerminalMarkers(text);
		text = unwrapDecoration(text);
		if (containsDiagnosticJunk(text) || looksLikeUnparsedCode(text)) {
			return "";
		}
		return text;
	}

	private static String normalizeSurface(String text) {
		String current = stripFences(text);
		for (int i = 0; i < 3; i++) {
			String next = unwrapQuotes(stripLabel(current)).trim();
			if (next.equals(current)) {
				break;
			}
			current = next;
		}
		return current.trim();
	}

	/** Obvious logger output or a stack trace can never be a visible translation. */
	public static boolean containsDiagnosticJunk(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		return LOG_LINE.matcher(text).find() || STACK_LINE.matcher(text).find()
				|| INTERNAL_LOG.matcher(text).find();
	}

	private static boolean looksLikeUnparsedCode(String text) {
		String value = text == null ? "" : text.trim();
		if (value.contains("```") || value.startsWith("<?xml") || value.startsWith("<html")) {
			return true;
		}
		if (value.startsWith("{") && value.endsWith("}")) {
			try {
				return com.google.gson.JsonParser.parseString(value).isJsonObject();
			} catch (RuntimeException ignored) {
				return value.contains("\":") || value.matches("(?s)^\\{\\s*[A-Za-z_$][\\w$.-]*\\s*:.*}$");
			}
		}
		return false;
	}

	private static String stripTerminalMarkers(String text) {
		String value = text == null ? "" : text.trim();
		String[] lines = value.split("\\R", -1);
		int end = lines.length;
		while (end > 0) {
			String marker = lines[end - 1].trim();
			if (marker.matches("(?i)^(?:-{3,}|={3,}|END|END OF (?:TRANSLATION|OUTPUT)|<END>|\\[END])$")) {
				end--;
			} else {
				break;
			}
		}
		return String.join("\n", java.util.Arrays.copyOf(lines, end)).trim();
	}

	private static String unwrapDecoration(String text) {
		String value = text == null ? "" : text.trim();
		for (int pass = 0; pass < 2 && value.length() >= 2; pass++) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if (first == '{' && last == '}' && isStructuredBraceEnvelope(value)) {
				// JSON/NBT is data. Only a symbolic wrapper such as {水} is removed.
				break;
			}
			boolean pair = (first == '(' && last == ')') || (first == '（' && last == '）')
					|| (first == '[' && last == ']') || (first == '【' && last == '】')
					|| (first == '{' && last == '}') || (first == '｛' && last == '｝')
					|| (first == '<' && last == '>') || (first == '＜' && last == '＞')
					|| (first == '《' && last == '》') || (first == '〈' && last == '〉')
					|| (first == '「' && last == '」') || (first == '『' && last == '』');
			if (!pair) {
				break;
			}
			value = value.substring(1, value.length() - 1).trim();
		}
		return value;
	}

	private static boolean isStructuredBraceEnvelope(String value) {
		try {
			if (com.google.gson.JsonParser.parseString(value).isJsonObject()) return true;
		} catch (RuntimeException ignored) {
			// SNBT permits unquoted keys and therefore is not valid JSON.
		}
		return value.contains("\":") || value.matches("(?s)^\\{\\s*[A-Za-z_$][\\w$.-]*\\s*:.*}$");
	}

	private static List<String> cleanList(List<String> values, int expected) {
		List<String> cleaned = new ArrayList<>(values.size());
		for (String value : values) {
			String text = cleanTranslation(value);
			if (text.isBlank()) {
				throw new ModelAnswerException("Expected " + expected + " translations but got 0");
			}
			cleaned.add(text);
		}
		return cleaned;
	}

	private static String unwrapTranslationEnvelope(String text) {
		try {
			com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(text);
			if (parsed.isJsonArray() && parsed.getAsJsonArray().size() == 1) {
				com.google.gson.JsonElement value = parsed.getAsJsonArray().get(0);
				return value.isJsonPrimitive() ? value.getAsString() : value.toString();
			}
			if (!parsed.isJsonObject()) {
				return null;
			}
			com.google.gson.JsonObject object = parsed.getAsJsonObject();
			for (String key : new String[] { "text", "translation", "translated", "value", "output", "content" }) {
				com.google.gson.JsonElement value = object.get(key);
				if (value != null && !value.isJsonNull()) {
					return value.isJsonPrimitive() ? value.getAsString() : value.toString();
				}
			}
		} catch (RuntimeException ignored) {
			// Ordinary prose is not JSON and is returned unchanged.
		}
		return null;
	}

	/** Drops a ```json … ``` fence, also when the closing fence never arrived. */
	public static String stripFences(String content) {
		if (content == null) {
			return "";
		}
		String text = content.trim();
		if (!text.startsWith("```")) {
			return text;
		}
		int firstNewline = text.indexOf('\n');
		if (firstNewline < 0) {
			return text;
		}
		int closing = text.lastIndexOf("```");
		String body = closing > firstNewline ? text.substring(firstNewline + 1, closing) : text.substring(firstNewline + 1);
		return body.trim();
	}

	/** Drops a leading "Translation:" / "译文：" label. */
	private static String stripLabel(String text) {
		String trimmed = text.trim();
		String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
		for (String label : new String[] { "translation:", "translated:", "translated text:", "output:", "result:",
				"answer:", "translation result:", "译文：", "译文:", "翻译：", "翻译:", "结果：", "结果:" }) {
			if (lower.startsWith(label)) {
				return trimmed.substring(label.length()).trim();
			}
		}
		return trimmed;
	}

	/** Removes one layer of matching surrounding quotes. */
	private static String unwrapQuotes(String text) {
		String trimmed = text.trim();
		if (trimmed.length() >= 2) {
			char first = trimmed.charAt(0);
			char last = trimmed.charAt(trimmed.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')
					|| (first == '“' && last == '”') || (first == '「' && last == '」')) {
				return trimmed.substring(1, trimmed.length() - 1).trim();
			}
		}
		return trimmed;
	}

	/** True when the answer is the input envelope the model was given. */
	private static boolean looksLikeInputEnvelope(String text) {
		if (!text.startsWith("{") && !text.startsWith("[")) {
			return false;
		}
		return text.contains("\"source_language\"") || text.contains("\"target_language\"")
				|| text.contains("\"texts\"");
	}

	private static boolean looksLikeEnvelopeField(String text) {
		String field = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
		return field.equals("texts") || field.equals("source_language") || field.equals("target_language")
				|| field.equals("translations") || field.equals("translation") || field.equals("output");
	}

	// ------------------------------------------------------------- strict

	/** The elements of a well formed JSON array, or {@code null}. */
	private static List<String> tryStrictArray(String text) {
		try {
			com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(text);
			if (!element.isJsonArray()) {
				return null;
			}
			List<String> out = new ArrayList<>();
			for (com.google.gson.JsonElement child : element.getAsJsonArray()) {
				appendElement(out, child);
			}
			return out;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * One element of an answer array.
	 * <p>
	 * Models also like to answer with objects ({@code [{"text": "译文"}]}) or with
	 * one extra array level ({@code [["译文"]]}); both are read as the string they
	 * carry, so the count still matches the request.
	 */
	private static void appendElement(List<String> out, com.google.gson.JsonElement element) {
		if (element == null || element.isJsonNull()) {
			out.add("");
			return;
		}
		if (element.isJsonPrimitive()) {
			out.add(element.getAsString());
			return;
		}
		if (element.isJsonArray()) {
			for (com.google.gson.JsonElement nested : element.getAsJsonArray()) {
				appendElement(out, nested);
			}
			return;
		}
		com.google.gson.JsonObject object = element.getAsJsonObject();
		for (String key : new String[] { "text", "translation", "translated", "value", "output" }) {
			com.google.gson.JsonElement value = object.get(key);
			if (value != null && value.isJsonPrimitive()) {
				out.add(value.getAsString());
				return;
			}
		}
		out.add(object.toString());
	}

	/** The first array of an object answer ({@code {"translations": [...]}}), or {@code null}. */
	private static String arrayInsideObject(String text) {
		if (!text.startsWith("{")) {
			return null;
		}
		try {
			com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(text);
			if (!element.isJsonObject()) {
				return null;
			}
			for (String key : new String[] { "translations", "translation", "texts", "results", "result", "data",
					"output" }) {
				com.google.gson.JsonElement value = element.getAsJsonObject().get(key);
				if (value != null && value.isJsonArray()) {
					return value.toString();
				}
			}
			for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : element.getAsJsonObject().entrySet()) {
				if (entry.getValue().isJsonArray()) {
					return entry.getValue().toString();
				}
			}
		} catch (RuntimeException e) {
			return null;
		}
		return null;
	}

	// ------------------------------------------------------------ salvage

	/**
	 * Reads the string elements of an array that is <em>almost</em> JSON.
	 * <p>
	 * The scanner walks the answer by hand so it survives the shapes a strict
	 * parser cannot read:
	 * <ul>
	 * <li>an unterminated array or string (a truncated answer);</li>
	 * <li>raw newlines and tabs inside a string, which models emit instead of
	 * {@code \n} - multi-line book pages are exactly the case that triggers it;</li>
	 * <li>input fields the model appended after the array
	 * ({@code ["译文", "source_language": "auto"]}) - a string that is followed by
	 * a colon is an object key, not a translation, so the scan stops there and
	 * drops it;</li>
	 * <li>one extra nesting level ({@code [["译文"]]});</li>
	 * <li>prose before the array.</li>
	 * </ul>
	 * The result is by construction free of trailing junk: the scan stops at the
	 * first character that cannot continue an array of strings.
	 */
	public static List<String> salvageArray(String content) {
		if (content == null) {
			return List.of();
		}
		int start = content.indexOf('[');
		if (start < 0) {
			return List.of();
		}
		int[] end = new int[1];
		return readArray(content, start, end);
	}

	private static List<String> readArray(String text, int open, int[] endOut) {
		List<String> out = new ArrayList<>();
		int i = open + 1;
		while (i < text.length()) {
			i = skipWhitespace(text, i);
			if (i >= text.length()) {
				break;
			}
			char c = text.charAt(i);
			if (c == ']') {
				i++;
				break;
			}
			if (c == ',') {
				i++;
				continue;
			}
			if (c == '"') {
				int[] end = new int[1];
				String value = readString(text, i, end);
				i = end[0];
				int next = skipWhitespace(text, i);
				if (next < text.length() && text.charAt(next) == ':') {
					// "source_language": "auto" - an input field, not a translation.
					i = next;
					break;
				}
				out.add(value);
				continue;
			}
			if (c == '[') {
				int[] end = new int[1];
				List<String> nested = readArray(text, i, end);
				out.addAll(nested);
				i = end[0];
				continue;
			}
			if (c == '{') {
				i = skipBraced(text, i);
				continue;
			}
			// Anything else: the array is malformed from here on, keep what we have.
			break;
		}
		endOut[0] = i;
		return out;
	}

	/** Reads a JSON string literal, including raw control characters and a missing closing quote. */
	private static String readString(String text, int start, int[] endOut) {
		StringBuilder sb = new StringBuilder();
		int i = start + 1;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length()) {
				char escaped = text.charAt(i + 1);
				switch (escaped) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case 'r' -> sb.append('\r');
					case 'b' -> sb.append('\b');
					case 'f' -> sb.append('\f');
					case 'u' -> {
						if (i + 5 < text.length()) {
							try {
								sb.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
								i += 6;
								continue;
							} catch (NumberFormatException e) {
								sb.append(escaped);
							}
						} else {
							sb.append(escaped);
						}
					}
					// \", \\, \/ - and any other escape: keep the character itself.
					default -> sb.append(escaped);
				}
				i += 2;
				continue;
			}
			if (c == '"') {
				i++;
				break;
			}
			// Raw newline/tab inside the string: kept as a normal character.
			sb.append(c);
			i++;
		}
		endOut[0] = i;
		return sb.toString();
	}

	/** Skips a balanced {...} block, respecting strings inside it. */
	private static int skipBraced(String text, int start) {
		int depth = 0;
		int i = start;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '"') {
				int[] end = new int[1];
				readString(text, i, end);
				i = end[0];
				continue;
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i + 1;
				}
			}
			i++;
		}
		return i;
	}

	private static int skipWhitespace(String text, int i) {
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
	}
}
