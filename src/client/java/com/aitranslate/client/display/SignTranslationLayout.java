package com.aitranslate.client.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.aitranslate.client.capture.ComponentExtractor;

/** Pure layout rules for translating one four-line sign as one cache entry. */
public final class SignTranslationLayout {
	public static final int MAX_LINES = 4;
	public static final int MAX_CHARS_PER_LINE = 15;

	private SignTranslationLayout() {
	}

	/** Keeps empty rows in the key so differently laid out signs never collide. */
	public static String cacheKey(List<String> lines) {
		List<String> fixed = fixed(lines);
		return String.join("\n", fixed);
	}

	/**
	 * Text sent to the model. Each row first uses the shared wrapped-text extractor,
	 * so {@code (death)} and {@code <sheep>} become {@code death} and {@code sheep}
	 * without sacrificing whole-sign context.
	 */
	public static String requestText(List<String> lines) {
		List<String> fixed = fixed(lines);
		List<String> request = new ArrayList<>(MAX_LINES);
		for (String line : fixed) {
			request.add(line.isBlank() ? "" : ComponentExtractor.translatableText(line));
		}
		return String.join("\n", request);
	}

	/**
	 * Reflows a whole-sign answer into exactly four render rows. Explicit newlines
	 * win; otherwise text is wrapped at word/punctuation boundaries and distributed
	 * over the rows occupied by the original where possible.
	 */
	public static List<String> layout(List<String> originalLines, String translated) {
		List<String> original = fixed(originalLines);
		if (translated == null || translated.equals(requestText(original))) {
			return original;
		}
		String normalized = translated.replace("\r\n", "\n").replace('\r', '\n').trim();
		if (normalized.isEmpty()) {
			return original;
		}
		List<String> explicitWrapped = explicitWrapped(original, normalized);
		if (explicitWrapped != null) {
			return explicitWrapped;
		}

		List<String> pieces = new ArrayList<>();
		for (String explicit : normalized.split("\n", -1)) {
			if (explicit.isBlank()) {
				if (normalized.indexOf('\n') >= 0) {
					pieces.add("");
				}
				continue;
			}
			pieces.addAll(wrap(explicit.trim(), MAX_CHARS_PER_LINE));
		}
		if (pieces.isEmpty()) {
			return original;
		}
		pieces = clamp(pieces);

		List<Integer> occupied = new ArrayList<>();
		for (int i = 0; i < original.size(); i++) {
			if (!original.get(i).isBlank()) {
				occupied.add(i);
			}
		}
		List<String> result = new ArrayList<>(Arrays.asList("", "", "", ""));
		if (!occupied.isEmpty() && pieces.size() <= occupied.size()) {
			for (int i = 0; i < pieces.size(); i++) {
				result.set(occupied.get(i), pieces.get(i));
			}
		} else {
			for (int i = 0; i < pieces.size(); i++) {
				result.set(i, pieces.get(i));
			}
		}
		return List.copyOf(result);
	}

	/** Reattaches each source row's symbols when the model preserves row boundaries. */
	private static List<String> explicitWrapped(List<String> original, String translated) {
		if (translated.indexOf('\n') < 0) {
			return null;
		}
		String[] rows = translated.split("\n", -1);
		List<Integer> occupied = new ArrayList<>();
		for (int i = 0; i < original.size(); i++) {
			if (!original.get(i).isBlank()) {
				occupied.add(i);
			}
		}
		List<Integer> targets = new ArrayList<>();
		if (rows.length == MAX_LINES) {
			for (int i = 0; i < MAX_LINES; i++) {
				targets.add(i);
			}
		} else if (rows.length == occupied.size()) {
			targets.addAll(occupied);
		} else {
			return null;
		}
		List<String> result = new ArrayList<>(Arrays.asList("", "", "", ""));
		for (int i = 0; i < rows.length && i < targets.size(); i++) {
			int target = targets.get(i);
			String source = original.get(target);
			String answer = rows[i].trim();
			if (source.isBlank() || answer.isBlank()) {
				continue;
			}
			String key = ComponentExtractor.translatableText(source);
			String innerAnswer = ComponentExtractor.translatableText(answer);
			String restored = ComponentExtractor.applyTranslation(source, java.util.Map.of(key, innerAnswer));
			result.set(target, fit(restored));
		}
		return List.copyOf(result);
	}

	private static String fit(String line) {
		if (line.codePointCount(0, line.length()) <= MAX_CHARS_PER_LINE) {
			return line;
		}
		int end = line.offsetByCodePoints(0, MAX_CHARS_PER_LINE - 1);
		return line.substring(0, end) + "…";
	}

	private static List<String> fixed(List<String> lines) {
		List<String> result = new ArrayList<>(MAX_LINES);
		for (int i = 0; i < MAX_LINES; i++) {
			String line = lines != null && i < lines.size() && lines.get(i) != null ? lines.get(i) : "";
			result.add(line);
		}
		return List.copyOf(result);
	}

	private static List<String> wrap(String text, int limit) {
		List<String> lines = new ArrayList<>();
		String rest = text.strip();
		while (!rest.isEmpty()) {
			if (rest.codePointCount(0, rest.length()) <= limit) {
				lines.add(rest);
				break;
			}
			int hardEnd = rest.offsetByCodePoints(0, limit);
			int breakAt = bestBreak(rest, hardEnd);
			String line = rest.substring(0, breakAt).stripTrailing();
			if (line.isEmpty()) {
				line = rest.substring(0, hardEnd);
				breakAt = hardEnd;
			}
			lines.add(line);
			rest = rest.substring(breakAt).stripLeading();
		}
		return lines;
	}

	private static int bestBreak(String text, int hardEnd) {
		for (int i = hardEnd; i > 0; i--) {
			char c = text.charAt(i - 1);
			if (Character.isWhitespace(c) || "，。！？；：,.!?;:".indexOf(c) >= 0) {
				return i;
			}
		}
		return hardEnd;
	}

	private static List<String> clamp(List<String> pieces) {
		if (pieces.size() <= MAX_LINES) {
			return List.copyOf(pieces);
		}
		List<String> result = new ArrayList<>(pieces.subList(0, MAX_LINES));
		String last = result.get(MAX_LINES - 1);
		if (last.codePointCount(0, last.length()) >= MAX_CHARS_PER_LINE) {
			int end = last.offsetByCodePoints(0, MAX_CHARS_PER_LINE - 1);
			last = last.substring(0, end) + "…";
		} else {
			last = last + "…";
		}
		result.set(MAX_LINES - 1, last);
		return List.copyOf(result);
	}
}
