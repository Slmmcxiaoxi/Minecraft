package com.aitranslate.client.scheduler;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;

/**
 * Decides whether a piece of hardcoded text is worth sending to the AI.
 * <p>
 * Everything that is obviously not translatable (numbers, coordinates, item
 * ids, real player names, text that is already Chinese, placeholder-only
 * strings) is skipped, which keeps the request count low and prevents the mod
 * from rewriting a player list.
 * <p>
 * Deliberately <em>not</em> in this list: "the text is a value of the vanilla
 * language file". That rule (removed in the fifteenth feedback round) skipped map
 * text such as the team prefix {@code [Guardian]} - see {@link #skipReason(String)}.
 */
public final class TextDetector {
	private static final Pattern URL = Pattern.compile("^(https?://|www\\.)\\S+$", Pattern.CASE_INSENSITIVE);
	private static final Pattern COMMAND = Pattern.compile("^[/\\\\].*");
	private static final Pattern DIGITS_SYMBOLS = Pattern.compile("^[\\d\\s\\p{Punct}\\p{S}]+$");
	private static final Pattern HAN = Pattern.compile("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]");
	private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30ff]");
	private static final Pattern HANGUL = Pattern.compile("[\\uac00-\\ud7af]");
	private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u052f]");
	private static final Pattern FORMATTING_CODES = Pattern.compile("(?i)\u00a7[0-9a-fk-or]");
	private static final Pattern TRANSLATION_KEY = Pattern.compile("^[a-z0-9_.]+\\.[a-z0-9_.]+$");
	private static final Pattern MINECRAFT_ID = Pattern.compile("^[a-z0-9_]+:[a-z0-9_/]+$");
	private static final Pattern LOG_OR_TIMESTAMP = Pattern.compile(
			"(?im)^\\s*(?:\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}|\\[?\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?]?)\\s*(?:\\[(?:TRACE|DEBUG|INFO|WARN|ERROR)]|(?:TRACE|DEBUG|INFO|WARN|ERROR)\\b)|^\\s*(?:Caused by:|Exception in thread|at [A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+\\([^)]*\\))");
	/** Shortest text worth translating (one character is never prose). */
	private static final int MIN_LENGTH = 2;

	/** Decorations a map author may wrap a virtual scoreboard entry in. */
	private static final Pattern WRAPPERS = Pattern.compile("^[<\\[({【（]+|[>\\])}】）]+$|[:：,，.。!！?？;；]+$");
	/** Prefixes that clearly mark a virtual object rather than a player. */
	private static final Pattern VIRTUAL_PREFIX = Pattern.compile("^[#$%*·•].*");
	/**
	 * Command <em>syntax</em>: a leading slash, a target selector ({@code @a}, {@code @p},
	 * {@code @s}, {@code @e}, {@code @r}) or relative coordinates ({@code ~ ~ ~},
	 * {@code ^1}). Only such text is treated as a command.
	 * <p>
	 * The first version of this filter also rejected any text that merely
	 * <em>started</em> with a command word, which silently broke map titles such as
	 * "Kill the Dragon" (starts with {@code kill}) or "Help the villagers" - the eighth
	 * feedback round's scoreboard regression. A message like
	 * "Command set: tell xxx Hello" is still protected, because that command is an
	 * argument of a vanilla message and is handled where the arguments are read
	 * ({@code ComponentExtractor}).
	 */
	private static final Pattern COMMAND_SYNTAX = Pattern.compile(
			"^/.*|.*@[apresd]\\b.*|.*(^|\\s)[~^][\\d.\\-]*(\\s|$).*", Pattern.DOTALL);

	/**
	 * An immutable snapshot avoids the brief empty window caused by clear()+addAll()
	 * while the request thread and render thread inspect the list concurrently.
	 */
	private volatile Set<String> playerNames = Set.of();
	private volatile String sourceLanguage = "auto";
	private volatile String targetLanguage = "zh_cn";

	public void setLanguages(String source, String target) {
		sourceLanguage = normalizeLanguage(source, "auto");
		targetLanguage = normalizeLanguage(target, "zh_cn");
	}

	public synchronized void setPlayerNames(Set<String> names) {
		Set<String> normalized = new LinkedHashSet<>();
		for (String name : names == null ? Set.<String>of() : names) {
			if (name != null && !name.isBlank()) {
				normalized.add(name.trim().toLowerCase(Locale.ROOT));
			}
		}
		playerNames = Set.copyOf(normalized);
	}

	/** Immediately protects a profile observed by a render hook, without waiting a tick. */
	public synchronized void protectPlayerName(String name) {
		if (name == null || name.isBlank()) {
			return;
		}
		String normalized = name.trim().toLowerCase(Locale.ROOT);
		if (playerNames.contains(normalized)) {
			return;
		}
		Set<String> updated = new LinkedHashSet<>(playerNames);
		updated.add(normalized);
		playerNames = Set.copyOf(updated);
	}

	public boolean hasPlayerNames() {
		return !playerNames.isEmpty();
	}

	/**
	 * Why a text is not translated, or {@code null} when it is.
	 * <p>
	 * {@link #shouldTranslate(String)} is the same decision with the reason thrown
	 * away. The reason exists for the debug log (twelfth feedback round): "this text
	 * was skipped" without saying why is what made the {@code <Death>} bug hard to
	 * see - the text was skipped by the placeholder filter, and nothing said so.
	 */
	public String skipReason(String text) {
		if (text == null) {
			return "no text";
		}
		String trimmed = text.trim();
		if (trimmed.length() < MIN_LENGTH) {
			return "shorter than " + MIN_LENGTH + " characters";
		}
		String stripped = FORMATTING_CODES.matcher(trimmed).replaceAll("").trim();
		if (stripped.length() < MIN_LENGTH) {
			return "only formatting codes";
		}
		// A leading #/$/% marks a virtual scoreboard or dialog label rather than a
		// player name - it still has to pass all the other checks below.
		boolean virtualEntry = VIRTUAL_PREFIX.matcher(stripped).matches();
		if (!virtualEntry && isPlayerName(stripped)) {
			return "player name";
		}
		if (stripped.codePoints().noneMatch(Character::isLetter)) {
			return "no letters (symbolic)";
		}
		if (DIGITS_SYMBOLS.matcher(stripped).matches()) {
			return "digits and symbols only";
		}
		if (URL.matcher(stripped).matches()) {
			return "url";
		}
		if (COMMAND.matcher(stripped).matches()) {
			return "starts with a slash";
		}
		// Command syntax (slash, target selector, relative coordinates). Plain prose
		// that merely starts with a command word ("Kill the Dragon") is NOT a command.
		if (COMMAND_SYNTAX.matcher(stripped).matches()) {
			return "command syntax";
		}
		if (MINECRAFT_ID.matcher(stripped).matches()) {
			return "minecraft id";
		}
		if (LOG_OR_TIMESTAMP.matcher(stripped).find()
				|| com.aitranslate.client.provider.ModelAnswers.containsDiagnosticJunk(stripped)) {
			return "log, timestamp or debug output";
		}
		if (TRANSLATION_KEY.matcher(stripped).matches()) {
			return "translation key";
		}
		// Fifteenth feedback round: the "text is a value of the vanilla language file"
		// veto used to stand here, and it is gone. It was aimed at text the game
		// localises itself - but the game only localises *translatable* components, and
		// those are not extracted as text at all unless the player opts in
		// (translateVanillaItemNames). What the veto really hit was map-authored literal
		// text that happens to match a vanilla word: the reported case is a scoreboard
		// team prefix "[Guardian] ", which the extractor reduces to "Guardian" - the name
		// of a vanilla mob - so the prefix stayed English while the suffix next to it was
		// translated. Every literal equal to a vanilla value ("Guardian", "Stone", "Save")
		// was affected. Since a literal is never localised by the game, skipping it only
		// ever left English text on screen; the structural protection against translating
		// data (String arguments of a translatable node, command arguments) is what
		// actually keeps vanilla messages intact (see ComponentExtractor#collectArgs).
		if (com.aitranslate.client.util.PlaceholderUtil.isPlaceholderOnly(stripped)) {
			return "placeholder only";
		}
		if (isMostlyTargetLanguage(stripped)) {
			return "already in the target language";
		}
		if (!LanguageHeuristics.matchesConfiguredSource(stripped, sourceLanguage)) {
			return "does not match configured source language " + sourceLanguage;
		}
		return null;
	}

	public boolean shouldTranslate(String text) {
		return skipReason(text) == null;
	}

	/**
	 * True when the text is a real player name (optionally wrapped in brackets or
	 * trailing punctuation, as scoreboards and team prefixes like to do).
	 */
	public boolean isPlayerName(String text) {
		if (text == null) {
			return false;
		}
		String normalized = FORMATTING_CODES.matcher(text).replaceAll("").trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return false;
		}
		if (isKnownPlayerName(normalized)) {
			return true;
		}
		String inner = WRAPPERS.matcher(normalized).replaceAll("").trim();
		return !inner.isEmpty() && isKnownPlayerName(inner);
	}

	/**
	 * Finds an online profile name embedded in a flattened server component such as
	 * {@code [Winner]Slmmcxiaoxi<Loser>}. Some multiplayer servers flatten the
	 * three vanilla team siblings into one literal before sending it.
	 */
	public String embeddedPlayerName(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String lower = FORMATTING_CODES.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
		String best = null;
		for (String candidate : playerNames) {
			if (candidate != null && !candidate.isBlank() && lower.contains(candidate)
					&& (best == null || candidate.length() > best.length())) {
				best = candidate;
			}
		}
		if (best != null) {
			return best;
		}
		try {
			Minecraft client = Minecraft.getInstance();
			if (client != null && client.getConnection() != null) {
				for (var info : client.getConnection().getOnlinePlayers()) {
					String name = info.getProfile().name();
					if (name != null && lower.contains(name.toLowerCase(Locale.ROOT))
							&& (best == null || name.length() > best.length())) {
						best = name;
					}
				}
			}
		} catch (RuntimeException | LinkageError ignored) {
			// Pure tests and the first multiplayer packet may not have a connection yet.
		}
		return best;
	}

	/**
	 * Checks both the last tick's snapshot and the live PlayerList. The latter closes
	 * the join-race where a chat/join line can render before the next client tick has
	 * copied the newly joined profile into the snapshot.
	 */
	private boolean isKnownPlayerName(String normalized) {
		if (playerNames.contains(normalized)) {
			return true;
		}
		try {
			Minecraft client = Minecraft.getInstance();
			if (client == null || client.getConnection() == null) {
				return false;
			}
			return client.getConnection().getOnlinePlayers().stream()
					.map(info -> info.getProfile().name())
					.filter(java.util.Objects::nonNull)
					.anyMatch(name -> name.equalsIgnoreCase(normalized));
		} catch (RuntimeException | LinkageError e) {
			// Player-name protection is also used by pure unit tests and during early
			// client start-up, where a live connection may not exist yet.
			return false;
		}
	}

	/** Target-aware check; Auto source must not reject non-Latin languages. */
	public boolean isMostlyTargetLanguage(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		String target = normalizeLanguage(targetLanguage, "zh_cn");
		if (target.startsWith("zh")) {
			// Japanese often contains Han too; any kana means it still needs Chinese translation.
			if (KANA.matcher(text).find() || HANGUL.matcher(text).find()) {
				return false;
			}
			return scriptRatio(text, HAN) >= 30;
		}
		if (target.startsWith("ja")) {
			return KANA.matcher(text).find();
		}
		if (target.startsWith("ko")) {
			return HANGUL.matcher(text).find();
		}
		if (target.startsWith("ru") || target.startsWith("uk") || target.startsWith("bg")) {
			return scriptRatio(text, CYRILLIC) >= 50;
		}
		// Latin-script languages cannot be distinguished reliably without a language
		// model. In Auto mode, trying the translation is safer than silently skipping
		// French/German/Spanish as if they were already English.
		return false;
	}

	private static int scriptRatio(String text, Pattern script) {
		int cjk = 0;
		int letters = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isLetter(c)) {
				letters++;
				if (script.matcher(String.valueOf(c)).matches()) {
					cjk++;
				}
			}
		}
		if (letters == 0) {
			return 0;
		}
		return cjk * 100 / letters;
	}

	private static String normalizeLanguage(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
	}
}
