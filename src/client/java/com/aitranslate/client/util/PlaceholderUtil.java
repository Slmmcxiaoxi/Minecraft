package com.aitranslate.client.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder handling: {@code %s}, {@code %1$s}, {@code {0}} and friends must
 * survive the round trip through the AI untouched. If the model drops or
 * invents a placeholder the translation is rejected and the original text is
 * kept.
 */
public final class PlaceholderUtil {
	/** {@code %s}, {@code %d}, {@code %1$s}, {@code %-2.3f}, {@code %%} ... */
	private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[-#+ 0,(<]*\\d*(\\.\\d+)?[a-zA-Z%]");
	/** {@code {0}}, {@code {1}}, {@code {0:%.2f}} - words in braces are display text. */
	private static final Pattern BRACE = Pattern.compile("\\{\\d+(?::[^{}]{1,32})?}");
	/** {@code ${...}} style placeholders used by some maps. */
	private static final Pattern DOLLAR_BRACE = Pattern.compile("\\$\\{[^{}]{1,32}}");

	private PlaceholderUtil() {
	}

	public static List<String> extract(String text) {
		List<String> found = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return found;
		}
		collect(BRACE, text, found);
		collect(DOLLAR_BRACE, text, found);
		String formatText = text;
		for (String placeholder : found) {
			formatText = formatText.replace(placeholder, " ".repeat(placeholder.length()));
		}
		collect(FORMAT, formatText, found);
		return found;
	}

	private static void collect(Pattern pattern, String text, List<String> out) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			String value = matcher.group();
			if ("%%".equals(value)) {
				continue;
			}
			if (!out.contains(value)) {
				out.add(value);
			}
		}
	}

	public static boolean containsAll(String text, List<String> placeholders) {
		if (placeholders == null || placeholders.isEmpty()) {
			return true;
		}
		if (text == null) {
			return false;
		}
		for (String placeholder : placeholders) {
			if (!text.contains(placeholder)) {
				return false;
			}
		}
		return true;
	}

	/** True when the text consists of placeholders, digits and punctuation only. */
	public static boolean isPlaceholderOnly(String text) {
		if (text == null || text.isBlank()) {
			return true;
		}
		String stripped = text;
		for (String placeholder : extract(text)) {
			stripped = stripped.replace(placeholder, "");
		}
		stripped = stripped.replaceAll("[\\s\\d\\p{Punct}\\p{S}]", "");
		return stripped.isEmpty();
	}

	/**
	 * True when one extracted placeholder <em>is</em> the whole text
	 * ({@code {0}}, {@code %s}) - as opposed to a text that merely
	 * contains one ("Hello %s").
	 * <p>
	 * Used by the symbol handling of the twelfth feedback round: a text that is
	 * nothing but a placeholder is never taken apart, so {@code {0}} keeps working
	 * exactly as before.
	 */
	public static boolean isFullTextPlaceholder(String text) {
		if (text == null) {
			return false;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		for (String placeholder : extract(trimmed)) {
			if (placeholder.equals(trimmed)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when the whole text is a placeholder by <em>syntax</em> rather than by the
	 * shape of the word inside it.
	 * <p>
	 * History, because this rule was wrong twice:
	 * <ul>
	 * <li>twelfth feedback round: {@code <Death>} was skipped because every
	 * {@code <identifier>} counted as a placeholder. The fix let a capitalised word
	 * through - which translated {@code <Death>} but left {@code <death>} behind,
	 * because a lowercase identifier was "obviously" a placeholder.</li>
	 * <li>fourteenth feedback round: that is exactly the reported bug
	 * ({@code <Death>} worked, {@code <death>} did not), and the document is explicit
	 * that a text must never be skipped because of its case, length or characters.
	 * So the case rule is gone.</li>
	 * </ul>
	 * What is left is syntax only, and it is unambiguous:
	 * <ul>
	 * <li>{@code %s}, {@code %1$s}, {@code %-2.3f} - the printf shapes;</li>
	 * <li>{@code {0}}, {@code {1}} - a <em>number</em> between braces;</li>
	 * <li>{@code ${name}} - the dollar-brace form some maps use.</li>
	 * </ul>
	 * Everything else inside symbols is a word and gets translated, whatever its
	 * case: {@code <death>}, {@code <DEATH>}, {@code {death}}. Real player names
	 * ({@code <Steve>}) are still protected, but by the player list rather than by a
	 * guess about the spelling (see {@code TextDetector}).
	 */
	public static boolean isPlaceholderMarker(String text) {
		if (text == null) {
			return false;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		if (trimmed.startsWith("${") && trimmed.endsWith("}") && trimmed.length() > 3) {
			return true;
		}
		for (String placeholder : extract(trimmed)) {
			if (!placeholder.equals(trimmed)) {
				continue;
			}
			if (BRACE.matcher(placeholder).matches()) {
				return true;
			}
			// Only the printf shapes are placeholders by definition. Everything else that
			// extract() can return - the {@code <...>} form - is display text inside
			// symbols, whatever it spells and whatever its case.
			return FORMAT.matcher(placeholder).matches();
		}
		return false;
	}

	/**
	 * Validates a translation against the original.
	 *
	 * @return {@code true} when the translation may be used.
	 */
	public static boolean validate(String original, String translated) {
		if (translated == null || translated.isBlank()) {
			return false;
		}
		List<String> placeholders = extract(original);
		if (!containsAll(translated, placeholders)) {
			return false;
		}
		// Guard against the model leaking instructions / adding markdown fences.
		String lower = translated.toLowerCase(java.util.Locale.ROOT);
		return !lower.contains("```") && !lower.startsWith("translation:");
	}
}
