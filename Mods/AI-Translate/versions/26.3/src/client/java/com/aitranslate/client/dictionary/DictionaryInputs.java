package com.aitranslate.client.dictionary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure parsing helpers shared by the dictionary editor and its tests. */
public final class DictionaryInputs {
	private DictionaryInputs() {
	}

	/**
	 * Splits an editor value on unescaped ASCII semicolons. Whitespace and empty
	 * pieces are discarded, duplicates retain their first position, and {@code \;}
	 * inserts a literal semicolon ({@code \\} inserts a literal backslash).
	 */
	public static List<String> splitOriginals(String input) {
		if (input == null || input.isBlank()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		StringBuilder part = new StringBuilder();
		boolean escaped = false;
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (escaped) {
				if (c != ';' && c != '\\') {
					part.append('\\');
				}
				part.append(c);
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else if (c == ';') {
				add(values, part);
			} else {
				part.append(c);
			}
		}
		if (escaped) {
			part.append('\\');
		}
		add(values, part);
		return List.copyOf(new LinkedHashSet<>(values));
	}

	private static void add(List<String> values, StringBuilder part) {
		String value = part.toString().trim();
		part.setLength(0);
		if (!value.isEmpty()) {
			values.add(value);
		}
	}
}
