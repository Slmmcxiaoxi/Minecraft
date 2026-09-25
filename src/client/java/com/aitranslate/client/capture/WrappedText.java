package com.aitranslate.client.capture;

import java.util.List;

/**
 * Splits text that is wrapped in symbols into "symbols + inner text"
 * (twelfth feedback round).
 * <p>
 * Reported problem: {@code [Death]} was translated while {@code <Death>} was not.
 * The asymmetry came from the placeholder filter, which treats anything that looks
 * like {@code <identifier>} as a placeholder that has to survive byte for byte -
 * a rule that skipped whole words - {@code <Death>} worked, {@code <death>} did not
 * (fourteenth feedback round: placeholders are recognised by syntax only now).
 * <p>
 * The fix is to stop guessing and take the symbols off before deciding anything:
 * the {@link #split(String) inner text} is what gets translated and judged, and
 * {@link Parts#rewrap(String)} puts the original symbols back around the result.
 * The game data is never touched - this only ever happens on the copy that is
 * handed to the renderer.
 *
 * <pre>
 * &lt;Death&gt;    -&gt; prefix "&lt;"  inner "Death"  suffix "&gt;"
 * [Kills]     -&gt; prefix "["  inner "Kills"  suffix "]"
 * &lt;[Death]&gt;  -&gt; prefix "&lt;[" inner "Death"  suffix "]&gt;"
 * (Welcome)   -&gt; prefix "("  inner "Welcome" suffix ")"
 * "Death"     -&gt; prefix "\"" inner "Death"  suffix "\""
 * [Quest] Kill the dragon -&gt; unchanged (not a wrapper: the "]" closes early)
 * </pre>
 *
 * Only the <em>outermost</em> run of symbols is removed, and a run is only removed
 * when it really brackets the whole text: the matching symbol has to sit on the
 * last position without the bracket having been closed before it. That is what
 * keeps {@code [Quest] Kill the dragon} - and every other text that merely starts
 * with a bracket - in one piece.
 */
public final class WrappedText {

	/** Symbols that open/close a run, in pairs: opener, closer. */
	private static final List<String[]> PAIRS = List.of(
			new String[] { "<", ">" },
			new String[] { "[", "]" },
			new String[] { "(", ")" },
			new String[] { "{", "}" },
			new String[] { "\"", "\"" },
			new String[] { "'", "'" },
			new String[] { "\u201c", "\u201d" },   // “ ”
			new String[] { "\u2018", "\u2019" },   // ‘ ’
			new String[] { "\u300a", "\u300b" },   // 《 》
			new String[] { "\u3010", "\u3011" },   // 【 】
			new String[] { "\u3008", "\u3009" },   // 〈 〉
			new String[] { "\u300c", "\u300d" },   // 「 」
			new String[] { "\u300e", "\u300f" },   // 『 』
			new String[] { "\uff08", "\uff09" },   // （ ）
			new String[] { "\uff3b", "\uff3d" },   // ［ ］
			new String[] { "\uff5b", "\uff5d" },   // ｛ ｝
			new String[] { "\uff1c", "\uff1e" });  // ＜ ＞

	/**
	 * Brackets that may also be removed from one side only ({@code <Death}).
	 * <p>
	 * Quotes are deliberately not in here: an apostrophe is ordinary punctuation
	 * ("the travellers' ledger"), and treating a lone one as a symbol would change
	 * how a lot of perfectly normal prose is translated.
	 */
	private static final List<String> ONE_SIDED_OPENERS = List.of("<", "[", "(", "{", "\uff08", "\uff3b", "\uff5b",
			"\uff1c");
	private static final List<String> ONE_SIDED_CLOSERS = List.of(">", "]", ")", "}", "\uff09", "\uff3d", "\uff5d",
			"\uff1e");

	/** How many nested runs are removed at most ({@code <[{(Death)}]>} is four). */
	private static final int MAX_LAYERS = 6;

	private WrappedText() {
	}

	/**
	 * A text with its outer symbols taken off.
	 *
	 * @param prefix the opening symbols, in their original order
	 * @param inner  the text between them
	 * @param suffix the closing symbols, in their original order
	 */
	public record Parts(String prefix, String inner, String suffix) {

		/** True when there was something to take off. */
		public boolean isWrapped() {
			return !prefix.isEmpty() || !suffix.isEmpty();
		}

		/** Puts the original symbols back around a translation. */
		public String rewrap(String translated) {
			return translated == null ? null : prefix + translated + suffix;
		}
	}

	/**
	 * The text with its outer symbols removed, or the text itself when there are none.
	 * <p>
	 * Surrounding whitespace is treated as part of the symbols (thirteenth feedback
	 * round): a literal like {@code "<Death> "} - a trailing space, which chat lines,
	 * book pages and scoreboard rows are full of - is the same case as
	 * {@code "<Death>"}. Before this it was not recognised as wrapped at all, so the
	 * placeholder filter still saw {@code <Death>} (it trims first) and skipped the
	 * text, while {@code "[Death] "} was translated - exactly the asymmetry that was
	 * reported twice. The whitespace is kept on the outside, so
	 * {@code rewrap(inner)} rebuilds the original text character for character.
	 */
	public static Parts split(String text) {
		if (text == null || text.isEmpty()) {
			return new Parts("", text == null ? "" : text, "");
		}
		StringBuilder prefix = new StringBuilder();
		StringBuilder suffix = new StringBuilder();
		String inner = text;
		for (int layer = 0; layer < MAX_LAYERS; layer++) {
			int from = 0;
			int to = inner.length();
			while (from < to && Character.isWhitespace(inner.charAt(from))) {
				from++;
			}
			while (to > from && Character.isWhitespace(inner.charAt(to - 1))) {
				to--;
			}
			String leading = inner.substring(0, from);
			String trailing = inner.substring(to);
			String core = inner.substring(from, to);
			String[] pair = enclosingPair(core);
			if (pair == null) {
				prefix.append(leading);
				suffix.insert(0, trailing);
				inner = core;
				break;
			}
			prefix.append(leading).append(pair[0]);
			// Closers are collected in reverse: reading the text from the outside in
			// gives "&lt;" then "[", so the closers have to be rebuilt as "]" then "&gt;".
			suffix.insert(0, pair[1] + trailing);
			inner = core.substring(pair[0].length(), core.length() - pair[1].length());
		}
		String rest = stripOneSided(inner, prefix, suffix);
		return new Parts(prefix.toString(), rest, suffix.toString());
	}

	/** The pair that brackets this text completely, or {@code null}. */
	private static String[] enclosingPair(String text) {
		for (String[] pair : PAIRS) {
			String open = pair[0];
			String close = pair[1];
			if (!text.startsWith(open) || !text.endsWith(close)) {
				continue;
			}
			if (text.length() <= open.length() + close.length()) {
				// Nothing between the symbols.
				continue;
			}
			if (open.equals(close) && text.length() < 3) {
				// A single quote character is not a wrapped text.
				continue;
			}
			if (closesEarly(text, open, close)) {
				// "[Quest] Kill the dragon": the bracket closes before the end, so the
				// text is not wrapped - it merely starts with a bracket.
				continue;
			}
			return pair;
		}
		return null;
	}

	/** True when the bracket depth returns to zero before the final symbol. */
	private static boolean closesEarly(String text, String open, String close) {
		if (open.equals(close)) {
			// Quotes: only the last position counts.
			return text.indexOf(close, open.length()) != text.length() - close.length();
		}
		int depth = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.startsWith(open, i)) {
				depth++;
			} else if (text.startsWith(close, i)) {
				depth--;
				if (depth == 0 && i != text.length() - close.length()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Removes a lone opening or closing symbol ({@code <Death}, {@code Death]}). */
	private static String stripOneSided(String text, StringBuilder prefix, StringBuilder suffix) {
		String rest = text;
		for (String open : ONE_SIDED_OPENERS) {
			if (!rest.startsWith(open)) {
				continue;
			}
			String close = closeFor(open);
			if (!rest.contains(close) && rest.length() > open.length()) {
				prefix.append(open);
				rest = rest.substring(open.length());
			}
			break;
		}
		for (String close : ONE_SIDED_CLOSERS) {
			if (!rest.endsWith(close)) {
				continue;
			}
			String open = openFor(close);
			if (!rest.contains(open) && rest.length() > close.length()) {
				suffix.insert(0, close);
				rest = rest.substring(0, rest.length() - close.length());
			}
			break;
		}
		return rest;
	}

	private static String closeFor(String open) {
		for (String[] pair : PAIRS) {
			if (pair[0].equals(open)) {
				return pair[1];
			}
		}
		return "";
	}

	private static String openFor(String close) {
		for (String[] pair : PAIRS) {
			if (pair[1].equals(close)) {
				return pair[0];
			}
		}
		return "";
	}
}
