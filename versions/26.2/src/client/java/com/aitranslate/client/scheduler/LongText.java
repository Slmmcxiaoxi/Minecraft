package com.aitranslate.client.scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a long text into translateable segments (tenth feedback round).
 * <p>
 * A book page (up to 1024 characters) or a paragraph of a dialog is a single
 * literal, so it used to travel to the API as one element. One 1000 character
 * element is a <em>much</em> slower request than four 250 character elements:
 * the model has to generate the whole answer before anything can be shown, the
 * request can outlive the timeout, and a single hiccup loses the entire page.
 * Splitting the text keeps every request small and, because each segment is
 * cached on its own, a re-opened page costs nothing.
 * <p>
 * The split is <strong>lossless by construction</strong>:
 * {@code String.join("", split(text, limit)).equals(text)} always holds - the
 * separators stay attached to the segment they follow. That invariant is what
 * lets the scheduler re-join the translated segments (or fall back to the
 * original segment for the ones that failed) without changing a single
 * character of the layout.
 */
public final class LongText {
	/**
	 * Hard cap on the number of segments. A 4000 character text with a 400
	 * character limit gives ten segments; a pathological text (a wall of "aaaa")
	 * must not turn into hundreds of requests.
	 */
	static final int MAX_SEGMENTS = 24;

	private LongText() {
	}

	/** True when the text has to be split before it is sent. */
	public static boolean needsSplit(String text, int limit) {
		return text != null && limit > 0 && text.length() > limit;
	}

	/**
	 * Splits {@code text} into segments of at most {@code limit} characters,
	 * preferring paragraph breaks, then sentence ends, then word boundaries.
	 * Returns a single element list when the text already fits.
	 */
	public static List<String> split(String text, int limit) {
		if (text == null || text.isEmpty()) {
			return text == null ? List.of() : List.of(text);
		}
		int max = limit <= 0 ? text.length() : limit;
		if (text.length() <= max) {
			return List.of(text);
		}
		int effective = Math.max(max, (text.length() + MAX_SEGMENTS - 1) / MAX_SEGMENTS);
		List<String> parts = new ArrayList<>();
		int start = 0;
		while (start < text.length()) {
			int end = start + effective;
			if (end >= text.length()) {
				parts.add(text.substring(start));
				break;
			}
			int cut = findCut(text, start, end);
			parts.add(text.substring(start, cut));
			start = cut;
		}
		return parts;
	}

	/**
	 * The cut position for a segment that must end at or before {@code hardEnd}.
	 * Searching starts at {@code start} so a segment can never come back empty.
	 */
	private static int findCut(String text, int start, int hardEnd) {
		int min = start + 1;
		// 1. paragraph break
		for (int i = hardEnd; i >= min; i--) {
			char c = text.charAt(i - 1);
			if (c == '\n') {
				return i;
			}
		}
		// 2. sentence end (kept with the sentence it ends)
		for (int i = hardEnd; i >= min; i--) {
			if (isSentenceEnd(text.charAt(i - 1))) {
				// Consume trailing spaces after the punctuation, no more.
				int j = i;
				while (j < text.length() && text.charAt(j) == ' ') {
					j++;
				}
				return j;
			}
		}
		// 3. clause / word boundary
		int best = -1;
		for (int i = hardEnd; i >= min; i--) {
			char c = text.charAt(i - 1);
			if (c == ',' || c == ';' || c == '、' || c == '，' || c == '；') {
				best = i;
				break;
			}
			if ((c == ' ' || c == '\t') && best < 0) {
				best = i;
				// keep looking for a comma-clause boundary, it reads better
			}
		}
		if (best > min) {
			return best;
		}
		// 4. hard cut - a single unbroken token longer than the limit
		int hard = hardEnd;
		if (hard <= min) {
			hard = min;
		}
		// Do not cut a surrogate pair in half.
		if (Character.isHighSurrogate(text.charAt(hard - 1)) && hard < text.length()
				&& Character.isLowSurrogate(text.charAt(hard))) {
			hard++;
		}
		return Math.min(hard, text.length());
	}

	private static boolean isSentenceEnd(char c) {
		return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '…'
				|| c == ';' || c == '；';
	}

	/**
	 * Re-assembles the segments. Segments without a translation (the request
	 * failed) keep their original text, so a partially translated long text mixes
	 * languages instead of losing its tail - and nothing is ever dropped.
	 */
	public static String join(List<String> parts, List<String> translations) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			String translated = translations != null && i < translations.size() ? translations.get(i) : null;
			out.append(translated == null || translated.isBlank() ? parts.get(i) : translated);
		}
		return out.toString();
	}
}
