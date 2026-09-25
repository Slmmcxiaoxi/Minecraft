package com.aitranslate.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Configuration migration.
 * <p>
	 * The version field is nullable because old JSON files do not contain it. The
	 * twenty-first feedback round replaces multi-key lists with four single keys.
 */
class ModConfigMigrationTest {

	@Test
	void oldConfigVersionMovesToSingleKeySchema() {
		ModConfig config = new ModConfig();
		config.configVersion = null;
		config.enableRangeDetection = true;

		config.fixup();

		assertEquals(85, config.toggleTranslateKey);
		assertEquals(79, config.openConfigKey);
		assertEquals(82, config.refreshTranslateKey);
		assertEquals(82, config.toggleOriginalKey);
		assertFalse(config.enableRangeDetection, "the version-3 accidental default is migrated off");
		assertEquals(ModConfig.CONFIG_VERSION, config.configVersion);
	}

	@Test
	void reboundSingleKeysAreKept() {
		ModConfig config = new ModConfig();
		config.configVersion = ModConfig.CONFIG_VERSION;
		config.toggleTranslateKey = 75;
		config.openConfigKey = 73;
		config.refreshTranslateKey = 76;
		config.toggleOriginalKey = -1;

		config.fixup();

		assertEquals(75, config.toggleTranslateKey);
		assertEquals(73, config.openConfigKey);
		assertEquals(76, config.refreshTranslateKey);
		assertEquals(-1, config.toggleOriginalKey);
	}

	@Test
	void brokenValuesAreRepaired() {
		ModConfig config = new ModConfig();
		config.configVersion = ModConfig.CONFIG_VERSION;
		config.maxRequestsPerSecond = 0;
		config.memoryCacheSize = -5;
		config.cacheRoot = "  ";

		config.fixup();

		assertTrue(config.maxRequestsPerSecond > 0);
		assertTrue(config.memoryCacheSize > 0);
		assertTrue(config.cacheRoot != null && !config.cacheRoot.isBlank());
	}

	@Test
	void versionFourMovesTheLegacyDictionaryDefaultOutOfCache() {
		ModConfig config = new ModConfig();
		config.configVersion = 4;
		config.dictionaryRoot = "config/ai_translate/cache";

		config.fixup();

		assertEquals("config/ai_translate/dictionary", config.dictionaryRoot);
		assertEquals(ModConfig.CONFIG_VERSION, config.configVersion);
	}

	/**
	 * Fifteenth feedback round: the in-screen button position is stored as an anchor
	 * plus offsets, and both are repaired rather than trusted - an unknown anchor from
	 * a hand-edited (or newer) file must fall back to the default corner instead of
	 * making the button vanish, and an absurd offset must be clamped so the button
	 * stays reachable.
	 */
	@Test
	void buttonPositionValuesAreRepaired() {
		ModConfig config = new ModConfig();
		config.configVersion = ModConfig.CONFIG_VERSION;
		config.buttonAnchor = "SOMEWHERE";
		config.buttonOffsetX = -99999;
		config.buttonOffsetY = 99999;

		config.fixup();

		assertEquals("TOP_RIGHT", config.buttonAnchor);
		assertEquals(-ModConfig.MAX_BUTTON_OFFSET, config.buttonOffsetX);
		assertEquals(ModConfig.MAX_BUTTON_OFFSET, config.buttonOffsetY);
	}

	@Test
	void buttonPositionDefaultsAreShippedOn() {
		ModConfig config = new ModConfig();

		assertTrue(config.showScreenButton, "the in-screen button is on by default");
		assertFalse(config.enableRangeDetection, "world-distance filtering is opt-in");
		assertEquals("TOP_RIGHT", config.buttonAnchor);
		assertEquals(-10, config.buttonOffsetX);
		assertEquals(10, config.buttonOffsetY);
	}

	@Test
	void apiKeysWithJsonAndUnicodeCharactersRoundTrip() {
		ModConfig config = new ModConfig();
		config.apiKey = "sk-\"反斜杠\\换行\nemoji😀";
		String json = com.aitranslate.client.util.FileUtil.GSON.toJson(config);
		ModConfig restored = com.aitranslate.client.util.FileUtil.GSON.fromJson(json, ModConfig.class);
		assertEquals(config.apiKey, restored.apiKey);
	}
}
