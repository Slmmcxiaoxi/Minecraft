package com.aitranslate;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main (shared) entrypoint of the AI Translate mod.
 * <p>
 * The mod is a pure client side overlay translator, so all logic lives in the
 * client source set; this initializer only publishes the mod id/name/logger and
 * logs that the shared stage came up.
 */
public class AITranslateMod implements ModInitializer {
	public static final String MOD_ID = "ai_translate";
	public static final String MOD_NAME = "AI Translate";
	/** Logger category used by every module log line. */
	public static final Logger LOGGER = LoggerFactory.getLogger("[AT]");

	@Override
	public void onInitialize() {
		LOGGER.info("Client overlay translator initialized");
	}
}
