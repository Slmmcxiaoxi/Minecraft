package com.aitranslate.client.capture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.aitranslate.client.util.PlaceholderUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Walks a {@link Component} tree and collects only the hardcoded {@code literal}
 * leaves, skipping {@code translatable} nodes (vanilla language keys, mod
 * language keys) and other dynamic contents.
 * <p>
 * The extractor is read-only: it returns the texts plus a way to rebuild a
 * display copy with the translated leaves, leaving the original untouched.
 * <p>
 * Special case handled here: maps (and old command blocks) very often store a
 * text component as a <em>JSON string</em> inside NBT, which the game turns into
 * a literal that reads {@code {"text":"Shadow Stalker","color":"red"}}. Such a
 * leaf is translated through its inner {@code text} value and the JSON around it
 * (colour, bold, extra siblings) is preserved - otherwise the map would show
 * "[译] {"text":"Shadow Stalker"}".
 */
public final class ComponentExtractor {
	private static final int MAX_DEPTH = 32;
	/** Literal text that looks like a serialised component. */
	private static final Pattern JSON_COMPONENT =
			Pattern.compile("^\\s*[\\[{].*\"(text|translate|extra|keybind|score)\".*[\\]}]\\s*$", Pattern.DOTALL);
	/** A vanilla text that interpolates values cannot be replaced by a literal. */
	private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(\\d+\\$)?[sdif]|\\{\\d+}");
	/** A multiplayer server may flatten the complete decorated chat line into one literal. */
	private static final Pattern FLATTENED_TEAM_CHAT = Pattern.compile(
			"^(?<open>\\s*<\\s*)?(?<prefix>\\[[^]\\r\\n]+])(?<name>[A-Za-z0-9_]{1,16})"
					+ "(?<suffix><[^>\\r\\n]+>)(?<close>\\s*>\\s*)?(?<body>.*)$",
			Pattern.DOTALL);
	/**
	 * Whether vanilla {@code translatable} text (item names, block names, …) is
	 * translated too. Off by default: the master rule is that the vanilla language
	 * file stays in charge, and it only fails to deliver when the player's client
	 * language is not the target language - which is exactly the case this switch is
	 * for (see {@code ModConfig#translateVanillaItemNames}).
	 */
	private static volatile boolean translateVanillaNames = false;
	/**
	 * Whether text wrapped in symbols ({@code <Death>}, {@code [Kills]}) is
	 * translated inside the symbols (twelfth feedback round). On by default: the
	 * reported behaviour was that {@code [Death]} worked while {@code <Death>} did
	 * not, which is exactly the kind of inconsistency a player notices immediately.
	 * Turning it off restores the old "translate the text including its symbols"
	 * behaviour.
	 */
	private static volatile boolean translateWrappedText = true;

	/**
	 * Switches translation of vanilla {@code translatable} names on or off.
	 * <p>
	 * Kept in a static field because the render path has no config reference; it is
	 * set from {@code AITranslateModClient} on startup and whenever the config is
	 * saved (see {@code ModConfig#translateVanillaItemNames}).
	 */
	public static void setTranslateVanillaNames(boolean enabled) {
		translateVanillaNames = enabled;
	}

	public static boolean translateVanillaNames() {
		return translateVanillaNames;
	}

	/**
	 * Switches translation of symbol wrapped text on or off
	 * ({@code <Death>} to {@code <死亡>}).
	 * <p>
	 * Static for the same reason as {@link #setTranslateVanillaNames(boolean)}. On by
	 * default, which is what the twelfth feedback round asked for; off restores the
	 * behaviour of the eleventh round, where the text including its symbols was sent
	 * as one piece.
	 */
	public static void setTranslateWrappedText(boolean enabled) {
		translateWrappedText = enabled;
	}

	public static boolean translateWrappedText() {
		return translateWrappedText;
	}

	private ComponentExtractor() {
	}

	/** Result of an extraction: the translatable texts in traversal order. */
	public record ExtractionResult(List<String> texts, boolean hasLiteral) {
		public boolean isEmpty() {
			return texts.isEmpty();
		}

		public static ExtractionResult empty() {
			return new ExtractionResult(List.of(), false);
		}
	}

	public static ExtractionResult extract(Component component) {
		if (component == null) {
			return ExtractionResult.empty();
		}
		List<String> texts = new ArrayList<>();
		collect(component, texts, 0);
		return new ExtractionResult(texts, !texts.isEmpty());
	}

	private static void collect(Component node, List<String> out, int depth) {
		if (node == null || depth > MAX_DEPTH) {
			return;
		}
		ComponentContents contents = node.getContents();
		if (contents instanceof PlainTextContents plain) {
			String text = plain.text();
			if (text != null && !text.isBlank()
					&& (JSON_COMPONENT.matcher(text).matches() || !isStructuredData(text))) {
				FlattenedTeamChat flattened = flattenedTeamChat(text);
				DecoratedLiteral decorated = flattened == null ? splitDecoratedPlayerLiteral(text) : null;
				if (flattened != null) {
					addDecoratedText(flattened.prefix(), out);
					addDecoratedText(flattened.suffix(), out);
					addDecoratedText(flattened.body(), out);
				} else if (decorated == null) {
					out.add(translatableText(text));
				} else {
					addDecoratedText(decorated.prefix(), out);
					addDecoratedText(decorated.suffix(), out);
				}
			}
		} else if (contents instanceof TranslatableContents translatable) {
			// The key itself is a language key and is never translated, but the
			// arguments can carry hardcoded text: /me, /tell, /msg and /say all
			// produce "<translatable prefix>" + arguments, which is exactly why
			// those outputs used to stay untranslated.
			collectArgs(translatable.getKey(), translatable.getArgs(), out, depth + 1);
			String english = vanillaNameOf(translatable);
			if (english != null) {
				out.add(english);
			}
		}
		// The text of a hover tooltip lives in the style, not in the node: without
		// this, "hover me" tooltips of chat lines, items and dialogs stayed English.
		HoverEvent hover = hoverOf(node);
		if (hover instanceof HoverEvent.ShowText showText) {
			collect(showText.value(), out, depth + 1);
		}
		for (Component sibling : node.getSiblings()) {
			collect(sibling, out, depth + 1);
		}
	}

	private static void addDecoratedText(String text, List<String> out) {
		if (text != null && !text.isBlank()) {
			String candidate = translatableText(text);
			if (candidate != null && !candidate.isBlank()) {
				out.add(candidate);
			}
		}
	}

	private record DecoratedLiteral(String prefix, String identity, String suffix) {
	}

	private record FlattenedTeamChat(String open, String prefix, String identity, String suffix, String close,
			String body) {
	}

	private static FlattenedTeamChat flattenedTeamChat(String text) {
		if (text == null) {
			return null;
		}
		var match = FLATTENED_TEAM_CHAT.matcher(text);
		if (!match.matches()) {
			return null;
		}
		return new FlattenedTeamChat(nullToEmpty(match.group("open")), match.group("prefix"), match.group("name"),
				match.group("suffix"), nullToEmpty(match.group("close")), nullToEmpty(match.group("body")));
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	/** Splits a server-flattened team name while keeping the profile immutable. */
	private static DecoratedLiteral splitDecoratedPlayerLiteral(String text) {
		var scheduler = com.aitranslate.client.AITranslateModClient.scheduler;
		if (scheduler == null || text == null || text.isBlank()) {
			return null;
		}
		String identity = scheduler.detector().embeddedPlayerName(text);
		if (identity == null || identity.isBlank()) {
			return null;
		}
		int index = text.toLowerCase(java.util.Locale.ROOT).indexOf(identity.toLowerCase(java.util.Locale.ROOT));
		if (index < 0) {
			return null;
		}
		String actual = text.substring(index, Math.min(text.length(), index + identity.length()));
		String prefix = text.substring(0, index);
		String suffix = text.substring(index + actual.length());
		if (prefix.isBlank() && suffix.isBlank()) {
			return null;
		}
		return new DecoratedLiteral(prefix, actual, suffix);
	}

	/**
	 * The English text of a vanilla {@code translatable} node, or {@code null} when
	 * vanilla names are not translated, the key is unknown, the text interpolates
	 * values, or a sibling/argument already supplies the text.
	 */
	private static String vanillaNameOf(TranslatableContents translatable) {
		if (!translateVanillaNames) {
			return null;
		}
		String key = translatable.getKey();
		if (key == null || key.startsWith("chat.") || key.startsWith("commands.")) {
			// Chat and command feedback keeps its vanilla wording (see collectArgs).
			return null;
		}
		Object[] args = translatable.getArgs();
		if (args != null && args.length > 0) {
			return null;
		}
		String english = com.aitranslate.client.scheduler.VanillaText.englishFor(key);
		if (english == null || english.isBlank() || FORMAT_SPECIFIER.matcher(english).find()) {
			return null;
		}
		return english;
	}

	/**
	 * The hover event of a node's style, or {@code null}. Click events are
	 * deliberately <em>not</em> walked: their payload is a command, a URL or an
	 * item to copy, and translating it would break the click instead of
	 * translating a visible text.
	 */
	private static HoverEvent hoverOf(Component node) {
		try {
			return node.getStyle() == null ? null : node.getStyle().getHoverEvent();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Arguments of a {@code translatable} node.
	 * <p>
	 * {@link Component} arguments are always walked - maps put their text there.
	 * <strong>String</strong> arguments are only walked for chat keys
	 * ({@code chat.type.*}), because everywhere else a String argument is data, not
	 * prose: vanilla passes raw commands, ids, paths and coordinates as strings.
	 * <p>
	 * Vanilla also hands a <em>command</em> over as a Component argument: "Command
	 * set: tell xxx Hello" is {@code advMode.setCommand.success} with the command as
	 * an argument. Such an argument is skipped too - but only here, because a map
	 * title that merely starts with a command word ("Kill the Dragon") is ordinary
	 * text and has to stay translatable.
	 */
	private static void collectArgs(String key, Object[] args, List<String> out, int depth) {
		if (args == null || depth > MAX_DEPTH) {
			return;
		}
		if (isOpaqueDataFeedbackKey(key)) {
			// /data output is a data dump. Even a literal description around its NBT is
			// part of that dump and must remain byte-for-byte readable.
			return;
		}
		boolean chatKey = isPlayerMessageKey(key);
		boolean commandFeedback = isCommandFeedbackKey(key);
		boolean lockedContainer = "container.isLocked".equals(key);
		for (int i = 0; i < args.length; i++) {
			Object arg = args[i];
			if (isTeamDisplayArgument(key, i, args.length)) {
				if (arg instanceof Component component) {
					collect(component, out, depth + 1);
				} else if (arg instanceof String text && !text.isBlank()) {
					out.add(translatableText(text));
				}
				continue;
			}
			if (arg instanceof Component component && isDecoratedPlayerArgument(key, i, args.length, component)) {
				collectDecoratedPlayer(component, out, depth + 1);
				continue;
			}
			if (isProtectedPlayerArgument(key, i, args.length)) {
				continue;
			}
			if (arg instanceof Component component) {
				if (commandFeedback && isCommandLike(component.getString())) {
					continue;
				}
				collect(component, out, depth + 1);
			} else if ((chatKey || lockedContainer) && arg instanceof String text && !text.isBlank()) {
				out.add(translatableText(text));
			}
		}
	}

	/**
	 * A sender component may be {@code prefix + real profile name + suffix}. The
	 * identity remains protected, but the two decorations are visible map text and
	 * must follow the same translation path as scoreboard team decorations.
	 */
	private static boolean isDecoratedPlayerArgument(String key, int index, int argumentCount,
			Component component) {
		boolean chatSender = isPlayerMessageKey(key) && argumentCount > 1 && index == argumentCount - 2;
		boolean joinOrLeave = isJoinLeaveKey(key) && index == 0;
		boolean deathVictim = isDeathMessageKey(key) && index == 0;
		boolean systemDecoratedName = isCommandFeedbackKey(key) && looksLikeDecoratedName(component);
		return (chatSender || joinOrLeave || deathVictim || systemDecoratedName) && component != null
				&& (component.getSiblings().size() >= 2 || flattenedTeamChat(component.getString()) != null);
	}

	private static boolean looksLikeDecoratedName(Component component) {
		if (component == null) return false;
		if (flattenedTeamChat(component.getString()) != null) return true;
		List<Component> parts = component.getSiblings();
		if (parts.size() == 1) parts = parts.get(0).getSiblings();
		if (parts.size() < 2) return false;
		var scheduler = com.aitranslate.client.AITranslateModClient.scheduler;
		if (scheduler != null) {
			for (Component part : parts) {
				if (scheduler.detector().isPlayerName(part.getString())) return true;
			}
		}
		return parts.size() >= 3 && (WrappedText.split(parts.get(0).getString()).isWrapped()
				|| WrappedText.split(parts.get(parts.size() - 1).getString()).isWrapped());
	}

	private static void collectDecoratedPlayer(Component component, List<String> out, int depth) {
		FlattenedTeamChat flattened = flattenedTeamChat(component.getString());
		if (flattened != null && component.getSiblings().isEmpty()) {
			addDecoratedText(flattened.prefix(), out);
			addDecoratedText(flattened.suffix(), out);
			addDecoratedText(flattened.body(), out);
			return;
		}
		List<Component> parts = component.getSiblings();
		// Real multiplayer packets wrap the decorated sender once more so the outer
		// component can carry SuggestCommand/ShowEntity/insertion style. Its only child
		// is the empty component that owns prefix, profile and suffix.
		if (parts.size() == 1 && parts.get(0).getSiblings().size() >= 2) {
			collectDecoratedPlayer(parts.get(0), out, depth + 1);
			return;
		}
		int identity = decoratedIdentityIndex(parts);
		for (int i = 0; i < parts.size(); i++) {
			if (i != identity) {
				collect(parts.get(i), out, depth + 1);
			}
		}
	}

	private static int decoratedIdentityIndex(List<Component> parts) {
		if (parts.isEmpty()) {
			return -1;
		}
		if (parts.size() == 1) {
			return 0;
		}
		var scheduler = com.aitranslate.client.AITranslateModClient.scheduler;
		if (scheduler != null) {
			for (int i = 0; i < parts.size(); i++) {
				if (scheduler.detector().isPlayerName(parts.get(i).getString())) {
					return i;
				}
			}
		}
		if (parts.size() >= 3) {
			return parts.size() / 2;
		}
		// One-sided decoration: bracketed text is normally a prefix; a leading-space
		// fragment is normally a suffix. If the shape is uncertain, protect the first
		// part, which is the conservative choice for a real player name.
		String first = parts.get(0).getString();
		String second = parts.get(1).getString();
		if (WrappedText.split(first).isWrapped()) {
			return 1;
		}
		if (second != null && !second.isEmpty() && Character.isWhitespace(second.charAt(0))) {
			return 0;
		}
		return 0;
	}

	/**
	 * Command feedback messages ({@code advMode.setCommand.success} = "Command set: %s",
	 * {@code commands.*}) carry a command as an argument - the one place where a
	 * component argument is data rather than text. Everywhere else a component
	 * argument is walked, so a map title like "Kill the Dragon" stays translatable.
	 */
	private static boolean isCommandFeedbackKey(String key) {
		return key != null && (key.startsWith("advMode.") || key.startsWith("commands."));
	}

	/**
	 * Player identities are protected structurally, before the name blacklist is
	 * consulted. Vanilla chat, team chat, private messages and join/leave messages
	 * put the message body last and the sender/recipient fields before it.
	 */
	private static boolean isProtectedPlayerArgument(String key, int index, int argumentCount) {
		if (key == null) {
			return false;
		}
		if (isJoinLeaveKey(key)) {
			return true;
		}
		if (isDeathMessageKey(key) && index == 0) {
			return true;
		}
		return isPlayerMessageKey(key) && argumentCount > 1 && index < argumentCount - 1;
	}

	private static boolean isJoinLeaveKey(String key) {
		return key != null && (key.startsWith("multiplayer.player.joined")
				|| key.startsWith("multiplayer.player.left"));
	}

	private static boolean isDeathMessageKey(String key) {
		return key != null && key.startsWith("death.");
	}

	private static boolean isOpaqueDataFeedbackKey(String key) {
		return key != null && (key.startsWith("commands.data.") || key.startsWith("commands.debug."));
	}

	/**
	 * Finds the real profile-name leaf inside a standalone decorated player name.
	 * Entity-name rendering uses this before translating the same component, so the
	 * team prefix/suffix remain candidates while the identity is protected even on
	 * the first frame after a player joins.
	 */
	public static String playerIdentity(Component component) {
		if (component == null) {
			return null;
		}
		FlattenedTeamChat flattened = flattenedTeamChat(component.getString());
		if (flattened != null && component.getSiblings().isEmpty()) {
			return flattened.identity();
		}
		List<Component> parts = component.getSiblings();
		if (parts.size() == 1 && parts.get(0).getSiblings().size() >= 2) {
			return playerIdentity(parts.get(0));
		}
		if (parts.size() >= 2) {
			int index = decoratedIdentityIndex(parts);
			if (index >= 0 && index < parts.size()) {
				return parts.get(index).getString();
			}
		}
		return component.getString();
	}

	/**
	 * NBT/JSON/diagnostic payloads are data, not prose. Braced words such as
	 * {@code {water}} deliberately do not match: a key/value separator is required.
	 */
	public static boolean isStructuredData(String text) {
		if (text == null) {
			return false;
		}
		String value = text.trim();
		if (value.length() < 3) {
			return false;
		}
		boolean compound = value.startsWith("{") && value.endsWith("}");
		boolean list = value.startsWith("[") && value.endsWith("]");
		if (!compound && !list) {
			return false;
		}
		// JSON/NBT compounds use key:value. Typed NBT arrays use [I;...], [B;...]
		// or [L;...]. A plain symbolic wrapper such as [Guild] remains prose.
		return value.indexOf(':') >= 0 || value.matches("^\\[[BILbil];.*]$");
	}

	/**
	 * {@code chat.type.team.text/sent} arguments are team display name, sender and
	 * message. The first one is map/server-authored visible text, not a player
	 * identity, so it must be translated even though the sender beside it stays
	 * protected.
	 */
	private static boolean isTeamDisplayArgument(String key, int index, int argumentCount) {
		return key != null && key.startsWith("chat.type.team.") && argumentCount >= 3 && index == 0;
	}

	private static boolean isPlayerMessageKey(String key) {
		return key != null && (key.startsWith("chat.type.")
				|| key.startsWith("commands.message.display."));
	}

	/** A vanilla command word at the start of an argument ("tell xxx Hello"). */
	private static final Pattern COMMAND_LIKE = Pattern.compile(
			"^/?(tell|say|msg|w|me|give|clear|setblock|fill|clone|summon|kill|tp|teleport|execute|"
					+ "scoreboard|team|tag|title|subtitle|actionbar|bossbar|advancement|attribute|data|"
					+ "datapack|function|schedule|effect|enchant|experience|xp|gamemode|difficulty|time|"
					+ "weather|playsound|stopsound|particle|locate|spawnpoint|setworldspawn|gamerule|"
					+ "item|recipe|reload|list|help|seed|dialog|waypoint|random|return|ride|damage|"
					+ "fillbiome|forceload|publish|transfer)\\b.*",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/**
	 * True when the text starts with a command word. Only consulted for the arguments
	 * of command feedback messages (see {@link #isCommandFeedbackKey}), because on its
	 * own it cannot tell a command from ordinary prose.
	 */
	public static boolean isCommandLike(String text) {
		return text != null && COMMAND_LIKE.matcher(text.trim()).matches();
	}

	/** Unique texts in traversal order. */
	public static List<String> uniqueTexts(Component component) {
		Map<String, Boolean> seen = new LinkedHashMap<>();
		for (String text : extract(component).texts()) {
			seen.putIfAbsent(text, Boolean.TRUE);
		}
		return new ArrayList<>(seen.keySet());
	}

	/**
	 * The text that has to be translated for a literal leaf: the text itself, the
	 * inner {@code text} value when the leaf is a serialised component, or the text
	 * <em>inside</em> the symbols it is wrapped in (twelfth feedback round).
	 * <p>
	 * The symbols are only taken off when they really bracket the whole text, and
	 * never for a text that is nothing but a placeholder - see
	 * {@link #symbolParts(String)}.
	 */
	public static String translatableText(String raw) {
		if (raw == null) {
			return null;
		}
		if (JSON_COMPONENT.matcher(raw).matches()) {
			String inner = firstJsonText(raw);
			if (inner == null || inner.isBlank()) {
				return raw;
			}
			// Thirteenth feedback round: the inner text of a serialised component goes
			// through the same symbol handling as a plain literal. Without this,
			// {"text":"<Death>"} produced the key "<Death>", the placeholder filter
			// recognised it as a placeholder and the text was never translated - the
			// "still broken" report, on the one path the twelfth round did not cover.
			return symbolParts(inner).inner();
		}
		return symbolParts(raw).inner();
	}

	/**
	 * The replacement for a literal leaf: the translated text, or - for a
	 * serialised component - the same JSON with its inner texts translated. Symbols
	 * the text was wrapped in are put back around the translation.
	 */
	public static String applyTranslation(String raw, Map<String, String> translations) {
		if (raw == null || translations == null || translations.isEmpty()) {
			return raw;
		}
		if (JSON_COMPONENT.matcher(raw).matches()) {
			try {
				JsonElement parsed = JsonParser.parseString(raw);
				if (parsed.isJsonObject()) {
					JsonObject copy = parsed.getAsJsonObject().deepCopy();
					return translateJson(copy, translations) ? copy.toString() : raw;
				}
				if (parsed.isJsonArray()) {
					var array = parsed.getAsJsonArray().deepCopy();
					boolean changed = false;
					for (int i = 0; i < array.size(); i++) {
						JsonElement element = array.get(i);
						if (element.isJsonObject()) {
							changed |= translateJson(element.getAsJsonObject(), translations);
						} else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
							String translated = leafTranslation(element.getAsString(), translations);
							if (translated != null) {
								array.set(i, new com.google.gson.JsonPrimitive(translated));
								changed = true;
							}
						}
					}
					return changed ? array.toString() : raw;
				}
			} catch (RuntimeException ignored) {
				// not valid JSON after all - fall through
			}
			return raw;
		}
		String translated = leafTranslation(raw, translations);
		return translated == null ? raw : translated;
	}

	/**
	 * The translation of one leaf, with the symbols of that leaf put back.
	 * <p>
	 * Used by every path that replaces a leaf - plain literals, the inner texts of a
	 * serialised component, the string elements of a JSON array and the arguments of
	 * a {@code chat.type.*} node - so all of them agree on what the key is and on how
	 * the result is put together. The symbol handling is deliberately symmetric with
	 * {@link #translatableText(String)}: whatever that method took off, this method
	 * puts back.
	 *
	 * @return the replacement text, or {@code null} when there is no translation
	 */
	public static String leafTranslation(String raw, Map<String, String> translations) {
		if (raw == null || translations == null || translations.isEmpty()) {
			return null;
		}
		if (JSON_COMPONENT.matcher(raw).matches()) {
			String translated = applyTranslation(raw, translations);
			return translated.equals(raw) ? null : translated;
		}
		WrappedText.Parts parts = symbolParts(raw);
		String translated = translations.get(parts.isWrapped() ? parts.inner() : raw);
		if (translated == null) {
			return null;
		}
		// Symbols and whitespace stay exactly as the map author wrote them, only the
		// inside is replaced - game data is untouched, this is the rendered copy.
		return parts.isWrapped() ? parts.rewrap(translated) : translated;
	}

	/**
	 * Takes the symbols off a literal so that the text inside them can be
	 * translated on its own, while the placeholder conventions keep their meaning.
	 * <p>
	 * Three cases are deliberately left whole:
	 * <ul>
	 * <li>{@code %s}, {@code {0}}, {@code ${name}} - a text that is nothing but a
	 * placeholder is data, not prose (the shapes are recognised by syntax only, never
	 * by the case of the word inside, see {@link PlaceholderUtil#isPlaceholderMarker});</li>
	 * <li>the feature switched off ({@code translateWrappedText}).</li>
	 * </ul>
	 * A player name inside symbols ({@code <Steve>}) is not decided here: the symbols
	 * come off, and the name filter of {@link TextDetector} then recognises the inner
	 * text as a player and skips it.
	 */
	private static WrappedText.Parts symbolParts(String raw) {
		WrappedText.Parts whole = new WrappedText.Parts("", raw, "");
		if (!translateWrappedText || raw.isEmpty()) {
			return whole;
		}
		if (looksLikeSerialisedData(raw)) {
			// {"foo": 1} and friends: data, not a text wrapped in braces.
			return whole;
		}
		WrappedText.Parts parts = WrappedText.split(raw);
		if (!parts.isWrapped() || parts.inner().isBlank()) {
			return whole;
		}
		if (PlaceholderUtil.isPlaceholderMarker(raw)) {
			// %s, {0}, ${gold}: a placeholder by syntax - data, never prose.
			// A word between symbols is a word whatever its case (fourteenth round:
			// "<death>" used to be kept whole because it looked like a variable name).
			return whole;
		}
		return parts;
	}

	/**
	 * True for a literal that is serialised data rather than a sentence in symbols:
	 * it starts with a brace or bracket <em>and</em> carries a quoted key
	 * ({@code {"foo": 1}}, {@code [{"text": "x"}]}). Such a text is left alone here -
	 * translatable component JSON is handled before this point, and everything else
	 * with a quoted key is a data structure that must keep its syntax.
	 */
	private static boolean looksLikeSerialisedData(String raw) {
		char first = raw.charAt(0);
		if (first != '{' && first != '[') {
			return false;
		}
		return raw.contains("\":") || raw.contains("\": ");
	}

	private static String firstJsonText(String raw) {
		try {
			JsonElement parsed = JsonParser.parseString(raw);
			if (parsed.isJsonObject()) {
				return textOf(parsed.getAsJsonObject());
			}
			if (parsed.isJsonArray()) {
				for (JsonElement element : parsed.getAsJsonArray()) {
					if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
						String text = element.getAsString();
						if (!text.isBlank()) {
							return text;
						}
					}
					if (element.isJsonObject()) {
						String text = textOf(element.getAsJsonObject());
						if (text != null && !text.isBlank()) {
							return text;
						}
					}
				}
			}
		} catch (RuntimeException ignored) {
			// handled by the caller
		}
		return null;
	}

	private static String textOf(JsonObject object) {
		if (object.has("text") && object.get("text").isJsonPrimitive()) {
			String text = object.get("text").getAsString();
			// A blank "text" is not the text of the component: {"text":"","extra":[…]} is
			// how maps write a styled multi part label, and the first real leaf is in
			// "extra" (thirteenth feedback round - taking the empty string made the whole
			// JSON blob the translation key, so nothing inside it was ever translated).
			if (!text.isBlank()) {
				return text;
			}
		}
		if (object.has("extra") && object.get("extra").isJsonArray()) {
			for (JsonElement element : object.getAsJsonArray("extra")) {
				if (element.isJsonObject()) {
					String text = textOf(element.getAsJsonObject());
					if (text != null && !text.isBlank()) {
						return text;
					}
				} else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
					String text = element.getAsString();
					if (!text.isBlank()) {
						return text;
					}
				}
			}
		}
		return null;
	}

	private static boolean translateJson(JsonObject object, Map<String, String> translations) {
		boolean changed = false;
		if (object.has("text") && object.get("text").isJsonPrimitive()) {
			String translated = leafTranslation(object.get("text").getAsString(), translations);
			if (translated != null) {
				object.addProperty("text", translated);
				changed = true;
			}
		}
		if (object.has("extra") && object.get("extra").isJsonArray()) {
			for (JsonElement element : object.getAsJsonArray("extra")) {
				if (element.isJsonObject()) {
					changed |= translateJson(element.getAsJsonObject(), translations);
				}
			}
		}
		return changed;
	}

	/**
	 * Builds a copy of {@code original} where every literal leaf is replaced by
	 * its translation. Styles, click events, hover events and translatable nodes
	 * are preserved; leaves without a translation keep the original text.
	 */
	public static MutableComponent rebuild(Component original, Map<String, String> translations) {
		return rebuild(original, translations, 0);
	}

	private static MutableComponent rebuild(Component node, Map<String, String> translations, int depth) {
		if (node == null || depth > MAX_DEPTH) {
			return Component.empty();
		}
		ComponentContents contents = node.getContents();
		MutableComponent result;
		if (contents instanceof PlainTextContents plain) {
			FlattenedTeamChat flattened = flattenedTeamChat(plain.text());
			DecoratedLiteral decorated = flattened == null ? splitDecoratedPlayerLiteral(plain.text()) : null;
			if (flattened != null) {
				result = Component.literal(flattened.open() + applyTranslation(flattened.prefix(), translations)
						+ flattened.identity() + applyTranslation(flattened.suffix(), translations)
						+ flattened.close() + applyTranslation(flattened.body(), translations));
			} else if (decorated == null) {
				result = Component.literal(applyTranslation(plain.text(), translations));
			} else {
				result = Component.literal(applyTranslation(decorated.prefix(), translations)
						+ decorated.identity() + applyTranslation(decorated.suffix(), translations));
			}
		} else if (contents instanceof TranslatableContents translatable) {
			String english = vanillaNameOf(translatable);
			String translatedName = english == null ? null : translations.get(english);
			if (translatedName != null) {
				// Vanilla name with a translation available: draw the translation as a
				// literal, keeping the node's style (colour, bold, italic).
				result = Component.literal(translatedName);
			} else {
				// Keep the key, translate the arguments.
				result = MutableComponent.create(new TranslatableContents(translatable.getKey(),
						translatable.getFallback(), translateArgs(translatable.getKey(), translatable.getArgs(),
								translations, depth + 1)));
			}
		} else {
			// score / selector / nbt / keybind: kept verbatim.
			result = MutableComponent.create(contents);
		}
		result.setStyle(translateHover(node.getStyle(), translations, depth + 1));
		for (Component sibling : node.getSiblings()) {
			result.append(rebuild(sibling, translations, depth + 1));
		}
		return result;
	}

	/**
	 * The node's style with its hover text translated. The style object itself is
	 * immutable and shared with the original component, so a new style is built
	 * and the original never changes.
	 */
	private static Style translateHover(Style style, Map<String, String> translations, int depth) {
		if (style == null) {
			return null;
		}
		HoverEvent hover = style.getHoverEvent();
		if (!(hover instanceof HoverEvent.ShowText showText) || showText.value() == null) {
			return style;
		}
		Component translated = rebuild(showText.value(), translations, depth + 1);
		return style.withHoverEvent(new HoverEvent.ShowText(translated));
	}

	private static Object[] translateArgs(String key, Object[] args, Map<String, String> translations, int depth) {
		if (args == null || args.length == 0) {
			return args;
		}
		if (isOpaqueDataFeedbackKey(key)) {
			return args;
		}
		// Same rule as collectArgs: String arguments of non-chat keys are data, and a
		// command passed as the argument of a command feedback message is never rewritten.
		boolean chatKey = isPlayerMessageKey(key);
		boolean commandFeedback = isCommandFeedbackKey(key);
		boolean lockedContainer = "container.isLocked".equals(key);
		Object[] translated = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			Object arg = args[i];
			if (isTeamDisplayArgument(key, i, args.length)) {
				if (arg instanceof Component component) {
					translated[i] = rebuild(component, translations, depth + 1);
				} else if (arg instanceof String text) {
					String replacement = leafTranslation(text, translations);
					translated[i] = replacement == null ? text : replacement;
				} else {
					translated[i] = arg;
				}
				continue;
			}
			if (arg instanceof Component component && isDecoratedPlayerArgument(key, i, args.length, component)) {
				translated[i] = rebuildDecoratedPlayer(component, translations, depth + 1);
				continue;
			}
			if (isProtectedPlayerArgument(key, i, args.length)) {
				translated[i] = arg;
				continue;
			}
			if (arg instanceof Component component) {
				translated[i] = commandFeedback && isCommandLike(component.getString())
						? arg
						: rebuild(component, translations, depth + 1);
			} else if ((chatKey || lockedContainer) && arg instanceof String text) {
				String replacement = leafTranslation(text, translations);
				translated[i] = replacement == null ? text : replacement;
			} else {
				translated[i] = arg;
			}
		}
		return translated;
	}

	private static MutableComponent rebuildDecoratedPlayer(Component component, Map<String, String> translations,
			int depth) {
		if (flattenedTeamChat(component.getString()) != null && component.getSiblings().isEmpty()) {
			return rebuild(component, translations, depth + 1);
		}
		MutableComponent result = MutableComponent.create(component.getContents());
		result.setStyle(translateHover(component.getStyle(), translations, depth + 1));
		List<Component> parts = component.getSiblings();
		if (parts.size() == 1 && parts.get(0).getSiblings().size() >= 2) {
			result.append(rebuildDecoratedPlayer(parts.get(0), translations, depth + 1));
			return result;
		}
		int identity = decoratedIdentityIndex(parts);
		for (int i = 0; i < parts.size(); i++) {
			result.append(i == identity ? parts.get(i).copy() : rebuild(parts.get(i), translations, depth + 1));
		}
		return result;
	}

	/** True when at least one leaf got a translation in this map. */
	public static boolean hasTranslation(Component component, Map<String, String> translations) {
		if (component == null || translations == null || translations.isEmpty()) {
			return false;
		}
		for (String text : extract(component).texts()) {
			String translated = translations.get(text);
			if (translated != null && !translated.equals(text)) {
				return true;
			}
		}
		return false;
	}
}
