package com.aitranslate.client;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.cache.CacheContext;
import com.aitranslate.client.cache.CacheManager;
import com.aitranslate.client.command.AITranslateCommand;
import com.aitranslate.client.config.ModConfig;
import com.aitranslate.client.keybinding.AITranslateKeybindings;
import com.aitranslate.client.provider.ProviderManager;
import com.aitranslate.client.scheduler.TranslationScheduler;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Client entrypoint: wires config, cache, provider, scheduler, key bindings and
 * the client command together and keeps the active cache context in sync with
 * the world/server the player is in.
 */
public class AITranslateModClient implements ClientModInitializer {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	public static ModConfig config;
	public static CacheManager cacheManager;
	public static ProviderManager providerManager;
	public static TranslationScheduler scheduler;
	public static com.aitranslate.client.dictionary.DictionaryManager dictionaryManager;
	public static AITranslateKeybindings keybindings;

	private static CacheContext activeContext;

	@Override
	public void onInitializeClient() {
		LOGGER.info("AI Translate client initializing");
		// Nothing here may throw: this runs while the game is still setting itself up,
		// and an exception at this point aborts the start-up (seventh feedback round).
		// Each step is also kept cheap - config file read, object construction and
		// event registration; no network, no world access and nothing that needs GLFW
		// (a GLFW call before the game initialises it crashes the game in
		// Window#checkGlfwError, which is what the round-6 start-up log line did).
		try {
			initConfig();
		} catch (RuntimeException | LinkageError e) {
			LOGGER.error("AI Translate could not initialise; the game continues without it", e);
			config = new ModConfig();
		}
		try {
			keybindings = new AITranslateKeybindings(config);
			AITranslateCommand.register();
			registerLifecycleHooks();
		} catch (RuntimeException | LinkageError e) {
			LOGGER.error("AI Translate could not register its key bindings or command", e);
		}

		LOGGER.info("AI Translate client initialized (translateEnabled={}, apiConfigured={})",
				config.translateEnabled, config.isApiConfigured());
		logKeyBindings();
	}

	/** Config, cache, provider and scheduler - the parts the mixins expect. */
	private static void initConfig() {
		config = ModConfig.load();
		com.aitranslate.client.config.StorageMigration.migrateLegacyDictionaries(
				net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("config/ai_translate/cache"),
				dictionaryRootDir());
		providerManager = new ProviderManager(config);
		cacheManager = new CacheManager(config);
		dictionaryManager = new com.aitranslate.client.dictionary.DictionaryManager(
				AITranslateModClient::dictionaryRootDir, () -> config != null && !config.dictionaryCaseSensitive);
		ensureStorageDirectories();
		scheduler = new TranslationScheduler(config, cacheManager, providerManager);
		scheduler.setInvalidator(AITranslateModClient::refreshRenderCaches);
		scheduler.setFailureNotifier(com.aitranslate.client.util.ApiFailureNotifier::onPermanentFailure);
		com.aitranslate.client.capture.ComponentExtractor
				.setTranslateVanillaNames(config.translateVanillaItemNames);
		com.aitranslate.client.capture.ComponentExtractor.setTranslateWrappedText(config.translateWrappedText);
		// The English text of vanilla keys is read in the background; until it is
		// loaded the opt-in "translate vanilla item names" simply finds no name.
		com.aitranslate.client.scheduler.VanillaText.load();
	}

	/** Creates the documented cache/dictionary roots on the first launch. */
	private static void ensureStorageDirectories() {
		try {
			java.nio.file.Files.createDirectories(cacheManager.getRootDir());
			java.nio.file.Files.createDirectories(dictionaryRootDir());
		} catch (java.io.IOException e) {
			LOGGER.warn("Could not create AI Translate storage directories; they will be retried on first write", e);
		}
	}

	/**
	 * Logs the effective single-key bindings. {@link com.aitranslate.client.keybinding.SingleKeyBinding#keyName}
	 * is deliberately free of GLFW calls (see the seventh feedback round's crash).
	 */
	private static void logKeyBindings() {
		try {
			LOGGER.info("AI Translate key bindings: toggle={} config={} refresh={} screen={} (configVersion={})",
					new com.aitranslate.client.keybinding.SingleKeyBinding(config.toggleTranslateKey).describe(),
					new com.aitranslate.client.keybinding.SingleKeyBinding(config.openConfigKey).describe(),
					new com.aitranslate.client.keybinding.SingleKeyBinding(config.refreshTranslateKey).describe(),
					new com.aitranslate.client.keybinding.SingleKeyBinding(config.toggleOriginalKey).describe(),
					config.configVersion);
		} catch (RuntimeException e) {
			LOGGER.warn("Could not describe the key bindings", e);
		}
	}

	/** Flush the cache on exit and log performance statistics when enabled. */
	private static void registerLifecycleHooks() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
				(handler, client) -> clearActiveContext());
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (cacheManager != null) {
				cacheManager.flushBlocking(3000L);
			}
			if (scheduler != null) {
				scheduler.shutdown();
			}
		});
	}

	/** Called every client tick; logs the performance summary every five minutes. */
	private static void aiTranslate$maybeLogPerformance(Minecraft client) {
		if (config == null || !config.logPerformanceStats || scheduler == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPerformanceLog < 300_000L) {
			return;
		}
		lastPerformanceLog = now;
		LOGGER.info("[perf] {} | 缓存 {} 条 / 命中率 {}%", scheduler.performanceSummary(),
				cacheManager == null ? 0 : cacheManager.currentSize(),
				cacheManager == null ? 0 : cacheManager.hitRatePercent());
	}

	private static volatile long lastPerformanceLog = System.currentTimeMillis();

	/**
	 * Called every client tick and drives everything that has to happen once per
	 * tick: the player name blacklist used by the text filter, the focus tracker
	 * (which decides request priority), the cache bucket of the current world, the
	 * periodic cache save, the optional performance line, the coalesced render
	 * refresh.
	 * <p>
	 * Everything here is render-layer bookkeeping; no game data is touched.
	 */
	public static void onClientTick(Minecraft client) {
		if (client == null) {
			return;
		}
		if (scheduler != null) {
			scheduler.onClientTick();
		}
		// Where is the player looking right now? This only drives request priority;
		// every enabled source is translated regardless of visibility.
		com.aitranslate.client.focus.FocusTracker.tick(client);
		// Forget the in-screen original/translation switch once its screen is gone
		// (thirteenth feedback round), so the next book starts with translations.
		com.aitranslate.client.display.ScreenOriginalMode.tick(client);
		// Every screen that shows the mod's text gets the clickable translation switch;
		// the key alone is not enough, because a text input captures it.
		com.aitranslate.client.display.ScreenToggleButton.tick(client);
		aiTranslate$detectTargetLanguage(client);
		updateContext(client);
		if (cacheManager != null) {
			cacheManager.saveIfDue(30_000L);
		}
		aiTranslate$maybeLogPerformance(client);
		// Coalesced refresh: at most one rebuild per tick, ten per second.
		com.aitranslate.client.display.RefreshCoordinator.tick(client);
	}

	/**
	 * Fills in the target language from the language the game is set to, once
	 * (thirteenth feedback round).
	 * <p>
	 * The feedback document asks for the player's game language as the default target
	 * while keeping the field editable: the value is only written when the config has
	 * none, which happens on a fresh install (the default is empty). A language the
	 * player typed in later is never overwritten - the field is theirs from then on.
	 */
	private static void aiTranslate$detectTargetLanguage(Minecraft client) {
		if (config == null || config.targetLang != null && !config.targetLang.isBlank()) {
			return;
		}
		try {
			var languages = client.getLanguageManager();
			if (languages == null || languages.getSelected() == null) {
				return;
			}
			config.targetLang = languages.getSelected();
			config.save();
			LOGGER.info("AI Translate target language taken from the game language: {}", config.targetLang);
		} catch (RuntimeException e) {
			LOGGER.warn("Could not read the game language", e);
		}
	}

	/** Switches the cache bucket when the player joins/leaves a world or server. */
	private static void updateContext(Minecraft client) {
		if (cacheManager == null || client == null) {
			return;
		}
		if (client.level == null) {
			clearActiveContext();
			return;
		}
		CacheContext context = resolveContext(client);
		if (context != null && !context.equals(activeContext)) {
			if (scheduler != null) scheduler.onContextChanged();
			cacheManager.switchContext(context);
			if (dictionaryManager != null) {
				dictionaryManager.switchContext(context);
			}
			activeContext = context;
			// Sixth feedback round: give the game (and the cache load that now runs in
			// the background) the first seconds after a world join, so the visible texts
			// are cache hits by the time translation starts instead of a burst of
			// requests during the initial load.
			if (scheduler != null) {
				scheduler.beginWarmUp(WORLD_JOIN_WARM_UP_MS);
			}
			aiTranslate$announceWarmUp(client);
			aiTranslate$warnAboutMissingApi();
			aiTranslate$warnAboutDisabledSwitch();
		}
	}

	/** Re-checks the world state immediately before a configuration screen is built. */
	public static void synchronizeContext(Minecraft client) {
		updateContext(client);
	}

	/** Clears cache, dictionary and client lifecycle state as one operation. */
	private static void clearActiveContext() {
		if (activeContext == null && (cacheManager == null || cacheManager.currentContext() == null)
				&& (dictionaryManager == null || !dictionaryManager.hasCurrentContext())) {
			return;
		}
		if (scheduler != null) scheduler.onContextChanged();
		if (cacheManager != null) cacheManager.switchContext(null);
		if (dictionaryManager != null) dictionaryManager.switchContext(null);
		activeContext = null;
	}

	private static final long WORLD_JOIN_WARM_UP_MS = 2500L;

	/** Tells the player that translation is starting up (sixth feedback round). */
	private static void aiTranslate$announceWarmUp(Minecraft client) {
		if (config == null || !config.showTranslationToast) {
			return;
		}
		com.aitranslate.client.display.RefreshCoordinator.toast(client, "[AT] 翻译服务初始化中…");
	}

	/**
	 * Without a Base URL and an API key nothing can be translated, and the request
	 * pipeline is not even started (see {@code TranslationScheduler#isEnabled}).
	 * Saying so once per world is friendlier than showing nothing at all
	 * (tenth feedback round: an explicit hint instead of silent failures).
	 */
	private static void aiTranslate$warnAboutMissingApi() {
		if (config == null || config.isApiConfigured()) {
			return;
		}
		com.aitranslate.client.util.ChatFeedback.send("尚未配置 API，当前显示原文。使用 /aitranslate config 设置。");
	}

	/** The cache bucket of the world the player is in right now, or {@code null}. */
	public static CacheContext currentContext() {
		return activeContext;
	}

	public static java.nio.file.Path dictionaryRootDir() {
		String configured = config == null ? "config/ai_translate/dictionary" : config.dictionaryRoot;
		java.nio.file.Path path = java.nio.file.Path.of(configured == null || configured.isBlank()
				? "config/ai_translate/dictionary" : configured);
		return path.isAbsolute() ? path
				: net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(path);
	}

	public static java.nio.file.Path storageRootDir() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ai_translate")
				.toAbsolutePath().normalize();
	}

	/**
	 * The master switch is a persisted setting, and the automatic switch-off after
	 * a run of API failures writes it to disk - so a player whose API was down
	 * yesterday enters the world today with translation still off. Saying so once
	 * per world (with the two ways to turn it back on) is cheaper than letting them
	 * wonder why nothing is translated (tenth feedback round).
	 */
	private static void aiTranslate$warnAboutDisabledSwitch() {
		if (config == null || config.translateEnabled) {
			return;
		}
		com.aitranslate.client.util.ChatFeedback.send("翻译已关闭。按 "
				+ new com.aitranslate.client.keybinding.SingleKeyBinding(config.toggleTranslateKey).describe()
				+ " 或使用 /aitranslate on 开启。");
	}

	/**
	 * The cache bucket for the current world: one per singleplayer save (keyed by the
	 * save directory) or per multiplayer address, so the same English line can have a
	 * different translation in a different map without ever colliding.
	 * <p>
	 * Falls back to the level name when the save directory cannot be read. The directory
	 * is preferred because two independent saves are allowed to have the same level name.
	 */
	public static CacheContext resolveContext(Minecraft client) {
		if (client.getSingleplayerServer() != null) {
			String name = null;
			java.nio.file.Path directory = null;
			try {
				name = client.getSingleplayerServer().getWorldData().getLevelName();
			} catch (RuntimeException ignored) {
				// The save directory below is sufficient on its own.
			}
			try {
				directory = client.getSingleplayerServer()
						.getWorldPath(net.minecraft.world.level.storage.LevelResource.LEVEL_DATA_FILE)
						.getParent();
			} catch (RuntimeException ignored) {
				// Fall back to the display name.
			}
			return CacheContext.singleplayer(directory, name);
		}
		ServerData server = client.getCurrentServer();
		if (server != null) {
			String address = server.ip != null && !server.ip.isBlank() ? server.ip : server.name;
			if (address == null || address.isBlank()) {
				address = "unknown_server";
			}
			return CacheContext.multiplayer(address);
		}
		return null;
	}

	/**
	 * Called when the visible result may have changed (new translations arrived, the
	 * config was edited, the master switch was flipped).
	 * <p>
	 * This does <em>not</em> refresh directly: it queues a coalesced refresh that
	 * runs once per client tick at most ten times per second, so a batch of twenty
	 * finished translations costs one rebuild instead of twenty
	 * (see {@link com.aitranslate.client.display.RefreshCoordinator}).
	 */
	public static void refreshRenderCaches() {
		com.aitranslate.client.display.RefreshCoordinator.request();
	}

	/**
	 * Refreshes immediately, on the client thread, including the caches that baked
	 * their text at build time (chat lines, advancement widgets, dialog layout).
	 * <p>
	 * This is what the manual refresh key and the master switch use: when the switch
	 * goes off, the lines already sitting in the chat have to go back to the original
	 * text right away (ninth feedback round).
	 */
	public static void refreshNow() {
		com.aitranslate.client.display.RefreshCoordinator.refreshNow(Minecraft.getInstance());
	}

	/**
	 * Called after the config was edited: re-render everything with the new settings.
	 * <p>
	 * A full refresh is requested (not a plain one) because saving the config can flip
	 * the master switch, and the baked caches would otherwise keep the old text.
	 */
	public static void onConfigChanged() {
		if (config != null) {
			// The extractor keeps its two behaviour switches in static fields (the
			// render path has no config reference), so they are re-applied here.
			com.aitranslate.client.capture.ComponentExtractor
					.setTranslateVanillaNames(config.translateVanillaItemNames);
			com.aitranslate.client.capture.ComponentExtractor.setTranslateWrappedText(config.translateWrappedText);
		}
		if (dictionaryManager != null) {
			dictionaryManager.reload();
		}
		com.aitranslate.client.display.RefreshCoordinator.requestFullRefresh();
	}

	/**
	 * Opens the config screen.
	 * <p>
	 * Two layers of fallback (sixth/seventh feedback round): Cloth Config is only a
	 * <em>suggested</em> dependency, and even with Cloth installed the screen building
	 * must never take the game down - if {@code ConfigScreen.create} throws, the player
	 * gets the small vanilla screen that explains the commands instead of a crash.
	 */
	public static void openConfigScreen(net.minecraft.client.gui.screens.Screen parent) {
		Minecraft client = Minecraft.getInstance();
		if (!clothConfigAvailable()) {
			com.aitranslate.client.util.ChatFeedback.send(
					"未安装 Cloth Config：配置界面不可用，请用 /aitranslate 命令修改设置，"
							+ "或安装 Cloth Config 后重开游戏。");
			// Same message as a screen: the player usually got here from a button.
			client.execute(() -> client.gui.setScreen(
					new com.aitranslate.client.config.MissingClothScreen(parent)));
			return;
		}
		client.execute(() -> {
			net.minecraft.client.gui.screens.Screen screen;
			try {
				screen = com.aitranslate.client.config.ConfigScreen.create(parent);
			} catch (RuntimeException | LinkageError e) {
				LOGGER.error("AI Translate could not build the config screen", e);
				com.aitranslate.client.util.ChatFeedback.send("配置界面构建失败: " + e);
				screen = new com.aitranslate.client.config.MissingClothScreen(parent);
			}
			client.gui.setScreen(screen);
		});
	}

	/** True when Cloth Config (the library behind the config screen) is loaded. */
	public static boolean clothConfigAvailable() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cloth-config");
		} catch (RuntimeException e) {
			return false;
		}
	}
}
