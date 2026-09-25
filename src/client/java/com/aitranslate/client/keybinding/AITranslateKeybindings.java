package com.aitranslate.client.keybinding;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.config.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Wires the four single-key bindings. World-level actions are edge-triggered by
 * {@link SingleKeyManager}; the in-screen key is handled by the screen hook.
 */
public class AITranslateKeybindings {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	public static final String TOGGLE_ID = "toggle_translate";
	public static final String CONFIG_ID = "open_config";
	public static final String REFRESH_ID = "refresh_translate";
	/**
	 * The in-screen switch (thirteenth feedback round). It is handled by a screen hook
	 * rather than by the per-tick bindings, because a screen suspends those - the id
	 * exists so the config screen can rebind it like the others.
	 */
	public static final String ORIGINAL_ID = "toggle_original";

	private final ModConfig config;
	private final SingleKeyManager manager = new SingleKeyManager();

	public AITranslateKeybindings(ModConfig config) {
		this.config = config;
		manager.add(TOGGLE_ID, this::toggleBinding, this::toggleTranslation);
		manager.add(CONFIG_ID, this::openConfigBinding, this::openConfigScreen);
		manager.add(REFRESH_ID, this::refreshBinding, this::refreshTranslation);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			manager.tick(client);
			AITranslateModClient.onClientTick(client);
		});
	}

	// ---------------------------------------------------------- bindings

	public SingleKeyBinding toggleBinding() {
		return new SingleKeyBinding(config.toggleTranslateKey);
	}

	public SingleKeyBinding openConfigBinding() {
		return new SingleKeyBinding(config.openConfigKey);
	}

	public SingleKeyBinding refreshBinding() {
		return new SingleKeyBinding(config.refreshTranslateKey);
	}

	/** The in-screen original/translation switch (thirteenth feedback round). */
	public SingleKeyBinding originalBinding() {
		return new SingleKeyBinding(config.toggleOriginalKey);
	}

	public void setOriginalBinding(SingleKeyBinding binding) {
		config.toggleOriginalKey = binding.keyCode();
		config.save();
	}

	public void setToggleBinding(SingleKeyBinding binding) {
		config.toggleTranslateKey = binding.keyCode();
		config.save();
	}

	public void setOpenConfigBinding(SingleKeyBinding binding) {
		config.openConfigKey = binding.keyCode();
		config.save();
	}

	public void setRefreshBinding(SingleKeyBinding binding) {
		config.refreshTranslateKey = binding.keyCode();
		config.save();
	}

	// ----------------------------------------------------------- actions

	/** Flips the master switch, persists it and reports through the action bar. */
	public void toggleTranslation() {
		config.translateEnabled = !config.translateEnabled;
		config.save();
		// Nothing was ever substituted into the game data, so the original text
		// reappears immediately - but only if the caches that baked the translated
		// text at build time are rebuilt. Chat lines, advancement widgets and dialog
		// layouts do exactly that, so this is a full (forced) refresh, not a plain
		// one: ninth feedback round, turning the switch off left the chat showing the
		// translated messages until something else happened to rebuild it.
		AITranslateModClient.refreshNow();
		if (config.showTranslationToast) {
			Minecraft client = Minecraft.getInstance();
			if (client.gui != null) {
				client.gui.setOverlayMessage(
						Component.literal(config.translateEnabled ? "[AT] 翻译已开启" : "[AT] 翻译已关闭"), false);
			}
		}
		LOGGER.info("AI Translate master switch: {}", config.translateEnabled ? "on" : "off");
	}

	public void openConfigScreen() {
		Minecraft client = Minecraft.getInstance();
		// Cloth Config is a suggested dependency; openConfigScreen explains that when
		// the library is missing instead of failing on the missing class.
		client.execute(() -> AITranslateModClient.openConfigScreen(client.screen));
	}

	/**
	 * Manual refresh: rebuilds every visible text from the current cache, optionally
	 * after dropping the in-memory translations so the next render asks the API
	 * again. The rebuild itself is render-layer only - the original data is never
	 * touched - and it is coalesced, so holding the key cannot stack up work.
	 */
	public void refreshTranslation() {
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			int dropped = 0;
			if (config.refreshClearsMemoryCache) {
				dropped = com.aitranslate.client.display.RefreshCoordinator.clearMemoryCaches();
			}
			AITranslateModClient.refreshNow();
			com.aitranslate.client.display.RefreshCoordinator.toast(client,
					dropped > 0 ? "[AT] 已刷新（清空内存缓存 " + dropped + " 条）" : "[AT] 已刷新");
			LOGGER.info("Manual refresh (clearedMemoryCache={})", dropped);
		});
	}
}
