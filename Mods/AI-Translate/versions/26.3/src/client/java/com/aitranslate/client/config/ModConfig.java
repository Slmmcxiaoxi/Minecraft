package com.aitranslate.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.DisplayMode;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persisted mod configuration ({@code config/ai_translate/ai_translate.json}).
 * <p>
 * Field defaults mirror the specification; every value can be edited from the
 * Cloth Config screen or the {@code /aitranslate} command.
 */
public class ModConfig {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	// ------------------------------------------------------------------ API
	public String apiBaseUrl = "https://api.deepseek.com";
	public String apiKey = "";
	public String model = "deepseek-chat";
	public String sourceLang = "auto";
	/**
	 * Target language. Empty means "use the language the game is set to", which is
	 * filled in once when the client starts (thirteenth feedback round) and then left
	 * alone - the player can change it here at any time and the detected value is
	 * never written over it.
	 */
	public String targetLang = "";
	/** Case-insensitive matching is the default; enable this only for strict dictionaries. */
	public boolean dictionaryCaseSensitive = false;
	/** Separate, configurable dictionary root; defaults beside the existing cache buckets. */
	public String dictionaryRoot = "config/ai_translate/dictionary";

	// -------------------------------------------------------- master switch
	public boolean translateEnabled = true;

	// ------------------------------------------------------- per source
	public boolean enableChatTranslate = true;
	/**
	 * Command output kinds of the chat (thirteenth feedback round).
	 * <p>
	 * The chat is a single text source in the render layer, but a player thinks in
	 * commands: {@code /me}, {@code /say}, {@code /msg} and a map's {@code /tellraw}
	 * output are different things, and a map that spoils its own story through a
	 * {@code /me} line is worth being able to switch off on its own. The kind of a
	 * line is read from the message it came from
	 * ({@link com.aitranslate.client.capture.ChatKind}), so these switches are real
	 * and not cosmetic.
	 */
	public boolean enableChatEmoteTranslate = true;
	public boolean enableChatAnnouncementTranslate = true;
	public boolean enableChatPrivateTranslate = true;
	/**
	 * {@code /tellraw} and every other hardcoded chat message the game delivers
	 * without a {@code chat.type.*} wrapper (map scripts, other mods). Named after the
	 * command because that is what a map author uses; technically it is "a chat
	 * message whose content is not a vanilla chat key".
	 */
	public boolean enableChatScriptTranslate = true;
	/**
	 * Command feedback and command block output.
	 * <p>
	 * Seventeenth feedback round: the switch for this kind was removed from the screen
	 * (the document's structure has no row for it) and its default is now
	 * {@code false} - command feedback is the game talking about commands, not map text.
	 * It stays in the config file for anyone who wants it back.
	 */
	public boolean enableCommandFeedbackTranslate = false;
	/**
	 * Item text: the name of the item in hand (the hotbar switch label, the tooltip
	 * title, the inventory name) <em>and</em> the tooltip of the item under the cursor.
	 * <p>
	 * Seventeenth feedback round: the two switches {@code enableItemTranslate} and
	 * {@code enableTooltipTranslate} were merged into this one - on screen they are the
	 * same thing ("物品文本": the text of an item), which is what the document asks for.
	 */
	public boolean enableItemTextTranslate = true;
	public boolean enableSignTranslate = true;
	public boolean enableBookTranslate = true;
	public boolean enableContainerTranslate = true;
	/**
	 * Titles, subtitles <em>and</em> the action bar.
	 * <p>
	 * Seventeenth feedback round: {@code enableActionBarTranslate} was merged into this
	 * one, because the action bar is the title's smaller sibling ({@code /title ...
	 * actionbar}) and the document lists them as one row.
	 */
	public boolean enableTitleTranslate = true;
	public boolean enableBossBarTranslate = true;
	public boolean enableScoreboardTranslate = true;
	public boolean enableEntityNameTranslate = true;
	public boolean enableDialogTranslate = true;
	public boolean enableTextDisplayTranslate = true;
	/** Advancement titles and descriptions (screen and toast). */
	public boolean enableAdvancementTranslate = true;
	/** Player list (Tab): custom display names, team prefix/suffix, header/footer. */
	public boolean enablePlayerListTranslate = true;

	// ------------------------------------------------------------ filters
	/**
	 * Translate vanilla {@code translatable} item/block names through the English
	 * text of their key (fourth feedback round: hovering an item in the inventory
	 * showed the original name).
	 * <p>
	 * This matters when the client language is not the target language: the game
	 * then shows "Diamond Sword" and only an AI translation can turn it into
	 * "钻石剑". Off by default because the master rule is that the vanilla language
	 * file stays in charge of vanilla text.
	 * <p>
	 * Fifteenth feedback round: the former {@code skipVanillaText} switch (which
	 * skipped any text that was byte for byte a vanilla language <em>value</em>) is
	 * gone. It was aimed at text the game localises itself, but the game only
	 * localises translatable components - what the switch actually skipped was
	 * map-authored literal text that happened to match a vanilla word, such as the
	 * reported scoreboard team prefix {@code [Guardian]} ("Guardian" is the name of a
	 * vanilla mob). A literal is never localised by the game, so skipping it only ever
	 * left English text on screen.
	 */
	public boolean translateVanillaItemNames = false;
	/**
	 * Turn the master switch off when the API stays unreachable after all retries,
	 * and tell the player (fourth feedback round).
	 * <p>
	 * Tenth feedback round: this is the <em>last</em> resort. A single failed
	 * request - which one long book page could cause on its own - no longer counts;
	 * the switch is only touched after {@link #failureThreshold} consecutive
	 * transport failures (a bad key or model name counts from the second one,
	 * because that will never fix itself).
	 */
	public boolean autoDisableOnApiFailure = true;

	// --------------------------------------------------------- display
	public String translationColor = "";
	public String translationPrefix = "";
	/**
	 * Show a toast when a batch of translations arrives.
	 * <p>
	 * Seventeenth feedback round: the switch was removed from the screen and the default
	 * is {@code false} - the text appearing in the target language is feedback enough,
	 * and a toast over a map scene is noise. The value stays in the config file.
	 */
	public boolean showTranslationToast = false;
	/**
	 * Verbose logging of every text source: replacements, skip reasons, batches and the
	 * per-page book log.
	 * <p>
	 * Seventeenth feedback round: the separate 书籍逐页日志 switch was merged into this one
	 * ({@link #logBookPages} is written together with it), so there is a single debug
	 * switch for maintenance work.
	 */
	public boolean debugLog = false;

	// --------------------------------------------------------- requests
	public int maxBatchSize = 20;
	/**
	 * Character budget of one request (tenth feedback round).
	 * <p>
	 * The element count alone was not enough: twenty book paragraphs of 1000
	 * characters each made a ~20 000 character request that a local model cannot
	 * answer before the timeout. With a character budget the same page travels as
	 * several small requests, each of which is fast, individually retryable and
	 * cacheable.
	 */
	public int maxBatchChars = 800;
	/**
	 * Gap between two requests (tenth feedback round). Keeps a page of text from
	 * arriving as a burst and gives the server time to breathe; the rate limit
	 * ({@link #maxRequestsPerSecond}) is the second, coarser bound.
	 */
	public int batchIntervalMs = 150;
	/**
	 * A text longer than this is split into segments before it is sent
	 * (tenth feedback round). Book pages are the typical case.
	 */
	public int maxTextSegmentChars = 400;
	public int maxRetries = 3;
	public int requestTimeoutSeconds = 30;
	/**
	 * Ceiling for the per-request timeout (tenth feedback round). The real timeout
	 * follows the payload size: a short sign line gets {@link #requestTimeoutSeconds},
	 * a full batch of long paragraphs may take this long. A request that runs into
	 * it is treated as "the server is still working", not as "the API is down".
	 */
	public int longRequestTimeoutSeconds = 120;
	/**
	 * How many requests may fail in a row (transport failures only) before the
	 * player is told and {@link #autoDisableOnApiFailure} may close the switch
	 * (tenth feedback round). Below it, a failure is silent: the text keeps its
	 * original and the queue keeps going.
	 */
	public int failureThreshold = 5;
	/** Maximum number of HTTP requests per second (rate limit). */
	public int maxRequestsPerSecond = 5;
	/** HTTP worker threads used by the provider (2-4 is plenty). */
	public int httpThreads = 2;
	/** Size of the in-memory translation cache (LRU eviction above this). */
	public int memoryCacheSize = 5000;
	/** Only world text inside this radius is queued; screen/HUD text is unaffected. */
	public boolean enableRangeDetection = false;
	/** Radius used for signs, entity names and text displays. */
	public int translationRangeBlocks = 64;
	/** Hard bound for work that has not started yet. */
	public int maxPendingTranslations = 1000;
	/** Log a performance summary every five minutes. */
	public boolean logPerformanceStats = false;
	/**
	 * Translate the page of a book and quill while it is being edited
	 * (tenth feedback round). Off by default: the editor is where the player
	 * <em>writes</em>, and a translated page would move the caret and hide what
	 * they are typing. Signed (written) books are always translated page by page.
	 */
	public boolean translateBookEditPage = false;
	/**
	 * Translate text that is wrapped in symbols (twelfth feedback round).
	 * <p>
	 * Reported problem: {@code [Death]} was translated while {@code <Death>} was
	 * not, because the placeholder filter treated anything that looks like
	 * {@code <identifier>} as a placeholder that must survive byte for byte. With
	 * this on, the symbols are taken off first, the text inside them is translated,
	 * and the original symbols are put back around the result:
	 * {@code <Death>} → {@code <死亡>}, {@code [Kills]} → {@code [击杀数]},
	 * {@code <[Death]>} → {@code <[死亡]>}.
	 * <p>
	 * What still stays untranslated is decided before the symbols come off:
	 * real placeholders ({@code %s}, {@code {0}}), the lowercase marker convention
	 * numbers between braces ({@code {0}}, {@code {1}}), player names
	 * ({@code <Steve>}, decided by the player list, not by the spelling), command
	 * syntax and item ids. Turning this off restores the behaviour of the eleventh
	 * round.
	 */
	public boolean translateWrappedText = true;
	/**
	 * Log every book page that is rendered, translated or left in its original
	 * language (eleventh feedback round).
	 * <p>
	 * The book bug ("page 19 and 20 never translate") was invisible in the log
	 * because a page that keeps its original text is silent by design: only the
	 * request that was sent shows up, and only with {@link #debugLog} on. Each line
	 * carries the page index, the page count, the character count, the cache/request
	 * state and the reason when a page failed.
	 * <p>
	 * Seventeenth feedback round: the switch is written together with {@link #debugLog}
	 * (the 书籍逐页日志 row was merged into 调试日志), so this field is kept for the file
	 * and for the command line rather than for the screen.
	 */
	public boolean logBookPages = false;

	// ------------------------------------------------------------ cache
	public String cacheRoot = "config/ai_translate/cache";

	// ------------------------------------------------------- key bindings
	/** Single-key bindings; {@code -1} means unbound. */
	public int toggleTranslateKey = 24; // U (Minecraft 26.3 scan code)
	public int openConfigKey = 18; // O
	/**
	 * Manual refresh key: rebuilds every visible text so translations that arrived
	 * late (or failed to refresh) show up.
	 */
	public int refreshTranslateKey = 21; // R
	/**
	 * In-screen switch between the translation and the original text (thirteenth
	 * feedback round). Works while a book, a book and quill, a dialog, the
	 * advancement screen or a container screen is open - where the master switch key
	 * cannot reach the player. Default {@code R}; it only fires when the screen does
	 * not use the key itself, so it never eats typed characters.
	 */
	public int toggleOriginalKey = 21; // R
	/** Whether the manual refresh also drops the in-memory translations. */
	public boolean refreshClearsMemoryCache = false;

	// ------------------------------------------- in-screen switch button (3.1)
	/**
	 * Whether the "译 / 原" button is drawn in screens at all (fifteenth feedback
	 * round: "GUI界面按钮：是/否" in 全局 → 按键绑定++ → 界面内切换).
	 * <p>
	 * The switch keeps working without it: the key binding above and the config
	 * screen's own controls do not depend on the button.
	 */
	public boolean showScreenButton = true;
	/**
	 * Where the button sits. One of {@code TOP_LEFT}, {@code TOP_RIGHT},
	 * {@code BOTTOM_LEFT}, {@code BOTTOM_RIGHT}, {@code CENTER} - stored as text so a
	 * hand-edited file stays readable; an unknown value falls back to
	 * {@code TOP_RIGHT} in {@link #fixup()}.
	 */
	public String buttonAnchor = "TOP_RIGHT";
	/**
	 * Offset of the button <em>from its anchor corner</em>, in GUI pixels, so the
	 * position survives a resolution change: a negative X moves the button away from
	 * a right anchor, a negative Y moves it away from a bottom anchor.
	 */
	public int buttonOffsetX = -10;
	public int buttonOffsetY = 10;

	/**
	 * Configuration layout version, used to migrate old defaults once.
	 * <p>
	 * {@code null} means "written before this field existed" - and it has to stay
	 * {@code null} when the key is absent from the file: Gson keeps the field
	 * initialiser of a missing key, so a plain {@code int} would look like "already
	 * migrated" and the migration would never run.
	 */
	public Integer configVersion;
	public static final int CONFIG_VERSION = 6;
	private transient boolean migrateLegacyKeyCodes;

	/** Largest offset a button position may carry (drag screen + hand-edited file). */
	public static final int MAX_BUTTON_OFFSET = 512;

	// ---------------------------------------------------------------------

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("ai_translate").resolve("ai_translate.json");
	}

	static Path legacyConfigPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("ai_translate.json");
	}

	public static ModConfig load() {
		migrateLegacyConfig();
		Path path = configPath();
		ModConfig config = new ModConfig();
		boolean rewriteLegacy = false;
		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				ModConfig loaded = com.aitranslate.client.util.FileUtil.GSON.fromJson(reader, ModConfig.class);
				if (loaded != null) {
					rewriteLegacy = loaded.configVersion == null || loaded.configVersion < CONFIG_VERSION;
					loaded.migrateLegacyKeyCodes = rewriteLegacy;
					config = loaded.fixup();
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Failed to read {} - using defaults", path, e);
			}
		} else {
			// A brand-new config needs the same normalization as an older file before it
			// is written. Saving first left configVersion null until the second launch.
			config.fixup();
			config.save();
		}
		if (rewriteLegacy) {
			// Rewriting once removes retired multi-key and visibility fields from an
			// older JSON file.
			config.save();
		}
		return config;
	}

	/** Repairs values that can be {@code null} after deserializing older files. */
	ModConfig fixup() {
		// Version 3 accidentally shipped range detection as enabled. Migrate that
		// short-lived default back to the requested opt-in behaviour. Once version 4
		// has been saved, a user's explicit choice is preserved normally.
		if (configVersion == null || configVersion < 4) {
			enableRangeDetection = false;
		}
		if (configVersion == null || configVersion < 5) {
			if (dictionaryRoot == null || dictionaryRoot.isBlank()
					|| dictionaryRoot.replace('\\', '/').equals("config/ai_translate/cache")) {
				dictionaryRoot = "config/ai_translate/dictionary";
			}
		}
		if (migrateLegacyKeyCodes || configVersion != null && configVersion < 6) {
			toggleTranslateKey = com.aitranslate.client.keybinding.LegacyKeyCodes.toMinecraftKey(toggleTranslateKey);
			openConfigKey = com.aitranslate.client.keybinding.LegacyKeyCodes.toMinecraftKey(openConfigKey);
			refreshTranslateKey = com.aitranslate.client.keybinding.LegacyKeyCodes.toMinecraftKey(refreshTranslateKey);
			toggleOriginalKey = com.aitranslate.client.keybinding.LegacyKeyCodes.toMinecraftKey(toggleOriginalKey);
			migrateLegacyKeyCodes = false;
		}
		if (configVersion == null || configVersion < CONFIG_VERSION) {
			configVersion = CONFIG_VERSION;
		}
		// Fifteenth feedback round: the button position. An unknown anchor (hand-edited
		// file, or a value written by a newer build) falls back to the default corner
		// instead of making the button disappear, and the offsets are clamped to a range
		// that can still be dragged back on screen.
		if (com.aitranslate.client.display.ButtonPlacement.Anchor.parse(buttonAnchor) == null) {
			buttonAnchor = "TOP_RIGHT";
		}
		if (Math.abs(buttonOffsetX) > MAX_BUTTON_OFFSET) {
			buttonOffsetX = Integer.signum(buttonOffsetX) * MAX_BUTTON_OFFSET;
		}
		if (Math.abs(buttonOffsetY) > MAX_BUTTON_OFFSET) {
			buttonOffsetY = Integer.signum(buttonOffsetY) * MAX_BUTTON_OFFSET;
		}
		if (cacheRoot == null || cacheRoot.isBlank()) {
			cacheRoot = "config/ai_translate/cache";
		}
		if (dictionaryRoot == null || dictionaryRoot.isBlank()) {
			dictionaryRoot = "config/ai_translate/dictionary";
		}
		if (maxRequestsPerSecond <= 0) {
			maxRequestsPerSecond = 5;
		}
		if (httpThreads <= 0) {
			httpThreads = 2;
		}
		if (memoryCacheSize <= 0) {
			memoryCacheSize = 5000;
		}
		translationRangeBlocks = Math.max(8, Math.min(256, translationRangeBlocks));
		maxPendingTranslations = Math.max(100, Math.min(5000, maxPendingTranslations));
		if (requestTimeoutSeconds <= 0) {
			requestTimeoutSeconds = 30;
		}
		if (maxBatchSize <= 0) {
			maxBatchSize = 20;
		}
		// Tenth feedback round: batching and timeout knobs. Clamped rather than
		// defaulted, so a hand-edited file cannot disable the character budget
		// (which is what keeps long texts from timing out).
		if (maxBatchChars <= 0) {
			maxBatchChars = 800;
		}
		if (maxBatchChars < 100) {
			maxBatchChars = 100;
		}
		if (batchIntervalMs < 0) {
			batchIntervalMs = 0;
		}
		if (batchIntervalMs > 5000) {
			batchIntervalMs = 5000;
		}
		if (maxTextSegmentChars <= 0) {
			maxTextSegmentChars = 400;
		}
		if (maxTextSegmentChars < 80) {
			maxTextSegmentChars = 80;
		}
		if (longRequestTimeoutSeconds <= 0) {
			longRequestTimeoutSeconds = 120;
		}
		if (longRequestTimeoutSeconds < requestTimeoutSeconds) {
			longRequestTimeoutSeconds = requestTimeoutSeconds;
		}
		if (failureThreshold <= 0) {
			failureThreshold = 5;
		}
		return this;
	}

	private static void migrateLegacyConfig() {
		Path oldPath = legacyConfigPath();
		Path newPath = configPath();
		if (!Files.isRegularFile(oldPath) || Files.exists(newPath)) return;
		try {
			Files.createDirectories(newPath.getParent());
			Path temporary = newPath.resolveSibling(newPath.getFileName() + ".migrating");
			Files.copy(oldPath, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			if (Files.size(oldPath) != Files.size(temporary)) {
				Files.deleteIfExists(temporary);
				throw new IOException("size verification failed");
			}
			try {
				Files.move(temporary, newPath, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temporary, newPath);
			}
			Path backup = oldPath.resolveSibling(oldPath.getFileName() + ".migrated.bak");
			if (Files.exists(backup)) {
				backup = oldPath.resolveSibling(oldPath.getFileName() + ".migrated-" + System.currentTimeMillis() + ".bak");
			}
			Files.move(oldPath, backup);
			LOGGER.info("Migrated AI Translate config {} -> {}; backup={}", oldPath, newPath, backup);
		} catch (IOException e) {
			LOGGER.warn("Could not migrate config {} -> {}; old file was preserved", oldPath, newPath, e);
		}
	}

	public void save() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				com.aitranslate.client.util.FileUtil.GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save {}", path, e);
		}
	}

	/**
	 * Whether a chat line of this kind is translated (thirteenth feedback round).
	 * <p>
	 * {@link TextType#CHAT} covers ordinary player chat only; the command kinds and
	 * the {@code /tellraw} / script messages have their own switches. The master
	 * switch and {@link #translateEnabled} are checked separately.
	 */
	public boolean isChatKindEnabled(com.aitranslate.client.capture.ChatKind kind) {
		return switch (kind) {
			case CHAT -> enableChatTranslate;
			case EMOTE -> enableChatEmoteTranslate;
			case ANNOUNCEMENT -> enableChatAnnouncementTranslate;
			case PRIVATE -> enableChatPrivateTranslate;
			case SCRIPT -> enableChatScriptTranslate;
			case COMMAND_FEEDBACK -> enableCommandFeedbackTranslate;
		};
	}

	public void setChatKindEnabled(com.aitranslate.client.capture.ChatKind kind, boolean enabled) {
		switch (kind) {
			case CHAT -> enableChatTranslate = enabled;
			case EMOTE -> enableChatEmoteTranslate = enabled;
			case ANNOUNCEMENT -> enableChatAnnouncementTranslate = enabled;
			case PRIVATE -> enableChatPrivateTranslate = enabled;
			case SCRIPT -> enableChatScriptTranslate = enabled;
			case COMMAND_FEEDBACK -> enableCommandFeedbackTranslate = enabled;
		}
	}

	/** Per-source enable switch (the master switch is checked separately). */
	public boolean isSourceEnabled(TextType type) {
		return switch (type) {
			case CHAT -> enableChatTranslate;
			// 物品文本: the item name and its tooltip are one source (round 17).
			case TOOLTIP, ITEM -> enableItemTextTranslate;
			case SIGN -> enableSignTranslate;
			case BOOK -> enableBookTranslate;
			case CONTAINER -> enableContainerTranslate;
			// 标题与动作栏 are one source (round 17): the action bar is the /title sibling.
			case TITLE, ACTION_BAR -> enableTitleTranslate;
			case BOSS_BAR -> enableBossBarTranslate;
			case SCOREBOARD -> enableScoreboardTranslate;
			case ENTITY_NAME -> enableEntityNameTranslate;
			case DIALOG -> enableDialogTranslate;
			case TEXT_DISPLAY -> enableTextDisplayTranslate;
			case ADVANCEMENT -> enableAdvancementTranslate;
			case PLAYER_LIST -> enablePlayerListTranslate;
		};
	}

	public void setSourceEnabled(TextType type, boolean enabled) {
		switch (type) {
			case CHAT -> enableChatTranslate = enabled;
			case TOOLTIP, ITEM -> enableItemTextTranslate = enabled;
			case SIGN -> enableSignTranslate = enabled;
			case BOOK -> enableBookTranslate = enabled;
			case CONTAINER -> enableContainerTranslate = enabled;
			case TITLE, ACTION_BAR -> enableTitleTranslate = enabled;
			case BOSS_BAR -> enableBossBarTranslate = enabled;
			case SCOREBOARD -> enableScoreboardTranslate = enabled;
			case ENTITY_NAME -> enableEntityNameTranslate = enabled;
			case DIALOG -> enableDialogTranslate = enabled;
			case TEXT_DISPLAY -> enableTextDisplayTranslate = enabled;
			case ADVANCEMENT -> enableAdvancementTranslate = enabled;
			case PLAYER_LIST -> enablePlayerListTranslate = enabled;
		}
	}

	/**
	 * The mod renders the translation in place of the original text, so there is a
	 * single display mode. The accessor is kept for caches, logs and the config UI.
	 */
	public DisplayMode displayModeFor(TextType type) {
		return DisplayMode.TRANSLATION_ONLY;
	}

	public boolean isApiConfigured() {
		return apiKey != null && !apiKey.isBlank() && apiBaseUrl != null && !apiBaseUrl.isBlank();
	}
}
