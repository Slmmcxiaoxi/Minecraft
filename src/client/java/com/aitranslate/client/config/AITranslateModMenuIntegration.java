package com.aitranslate.client.config;

import com.aitranslate.client.AITranslateModClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu entry point: opens the Cloth Config screen.
 * <p>
 * Cloth Config is a suggested dependency, so the factory delegates to
 * {@link AITranslateModClient#openConfigScreen(Screen)}, which reports the missing
 * library instead of failing on a missing class.
 */
public class AITranslateModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> {
			try {
				return AITranslateModClient.clothConfigAvailable()
						? ConfigScreen.create(parent)
						: new MissingClothScreen(parent);
			} catch (RuntimeException | LinkageError e) {
				// A broken config screen must never take the game down.
				com.aitranslate.AITranslateMod.LOGGER.error("Could not build the config screen", e);
				return new MissingClothScreen(parent);
			}
		};
	}
}
