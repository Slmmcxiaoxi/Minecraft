package com.aitranslate.client.scheduler;

/**
 * Sanity checks on an answer of the translation model.
 * <p>
 * Fifteenth feedback round, the second half of "the team prefix and suffix are not
 * translated again": the prefix was skipped by the vanilla-value filter, and the
 * <em>suffix</em> was broken by the cache. The model had answered the short fragment
 * "the Brave" with "the Brave" - an echo - and that answer was stored as a
 * translation. From then on the text was a cache hit, so it was never asked again and
 * the player kept seeing English while everything around it was translated. The same
 * happened to other short fragments ("the Bold", "aitrans_demo", "AT").
 *
 * <h2>What counts as an echo</h2>
 * An answer equal to its input is <em>not</em> automatically wrong: "AT", "aitrans_demo"
 * or a player name are correctly left alone, and a model that returns "OK" for "OK" is
 * right. The rule here is deliberately narrow:
 *
 * <ul>
 * <li>only whole answers are compared (after trimming and removing formatting codes);</li>
 * <li>only text that reads as prose is checked - it has to contain Latin letters and
 * at least two words. Single tokens (ids, codes, names, "AT") keep their identity
 * answer, because that answer is usually the correct one;</li>
 * <li>text that is already in the target language is not checked either.</li>
 * </ul>
 *
 * Everything else is treated as an unusable answer: it is not written to the cache, it
 * counts as an answer failure (retried after the answer-failure delay) and it is named
 * in the debug log. The effect is that a short fragment can never be permanently
 * frozen in English by one bad answer.
 */
public final class AnswerQuality {

	private static final java.util.regex.Pattern FORMATTING_CODES =
			java.util.regex.Pattern.compile("(?i)\u00a7[0-9a-fk-or]");
	private static final java.util.regex.Pattern LATIN_LETTER =
			java.util.regex.Pattern.compile("[A-Za-z]");
	/** Words have to be separated by whitespace to count as prose here. */
	private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
	private static final java.util.regex.Pattern HANGUL = java.util.regex.Pattern.compile("[\\u1100-\\u11ff\\u3130-\\u318f\\uac00-\\ud7af]");
	private static final java.util.regex.Pattern KANA = java.util.regex.Pattern.compile("[\\u3040-\\u30ff\\u31f0-\\u31ff]");

	private AnswerQuality() {
	}

	/** Normalises an answer for the comparison: no formatting codes, no outer spaces. */
	private static String normalize(String text) {
		if (text == null) {
			return "";
		}
		return FORMATTING_CODES.matcher(text).replaceAll("").trim();
	}

	/**
	 * True when the answer repeats the input although the input is prose that has to
	 * be translated.
	 *
	 * @param original   the text that was sent
	 * @param translated what the model answered
	 * @param targetLanguageIsPresent whether the original already looks like the target
	 *                               language (then an identical answer is correct)
	 */
	public static boolean isEcho(String original, String translated, boolean targetLanguageIsPresent) {
		if (original == null || translated == null || targetLanguageIsPresent) {
			return false;
		}
		String left = normalize(original);
		String right = normalize(translated);
		if (left.isEmpty() || !left.equals(right)) {
			return false;
		}
		if (!LATIN_LETTER.matcher(left).find()) {
			// Numbers, symbols, ids without letters: nothing to translate.
			return false;
		}
		if (WHITESPACE.split(left).length > 1) {
			// Two or more words: a phrase the model was supposed to translate.
			return true;
		}
		// A single token: ids ("AT", "HP", "aitrans_demo", "minecraft:stone"), codes and
		// names legitimately come back unchanged, so those are never echoes.
		if (left.length() < SINGLE_WORD_LIMIT || looksLikeIdentifier(left)) {
			return false;
		}
		// A long word without id characters is prose ("Guidance"); an unchanged answer
		// there is a real failure.
		return left.chars().anyMatch(Character::isLowerCase);
	}

	/** True for tokens that are ids rather than words (namespace:path, snake_case, …). */
	private static boolean looksLikeIdentifier(String token) {
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			if (c == '_' || c == ':' || c == '/' || c == '.' || c == '-') {
				return true;
			}
		}
		return false;
	}

	/** Single tokens shorter than this are never treated as an echo. */
	private static final int SINGLE_WORD_LIMIT = 8;

	/**
	 * True when a <em>cached</em> entry looks like a stored echo and should be dropped
	 * instead of served (the repair path for caches written by an older build).
	 */
	public static boolean isStoredEcho(String original, String translated) {
		return isEcho(original, translated, false);
	}

	/**
	 * Reject an unmistakable foreign writing system for Chinese targets. Latin
	 * letters are deliberately allowed because names, ids and placeholders may be
	 * embedded in an otherwise valid Chinese translation.
	 */
	public static boolean hasClearlyWrongScript(String translated, String targetLanguage) {
		if (translated == null || translated.isBlank()) {
			return false;
		}
		String target = targetLanguage == null ? "" : targetLanguage.trim().toLowerCase(java.util.Locale.ROOT)
				.replace('-', '_');
		if (target.equals("zh") || target.startsWith("zh_")) {
			return HANGUL.matcher(translated).find() || KANA.matcher(translated).find();
		}
		return false;
	}
}
