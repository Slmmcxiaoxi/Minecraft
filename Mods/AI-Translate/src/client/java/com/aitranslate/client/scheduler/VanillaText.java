package com.aitranslate.client.scheduler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The vocabulary of the vanilla language file: key to English text.
 * <p>
 * Used for one thing since the fifteenth feedback round: the opt-in
 * {@code translateVanillaItemNames}. A vanilla item name is a {@code translatable}
 * key, and the game only shows it in the target language when the client language
 * happens to be the target language; with the opt-in, the key is turned into its
 * English text ({@link #englishFor(String)}) and translated like any other text.
 * <p>
 * <strong>What was removed:</strong> the "this text is a vanilla language value, so
 * do not translate it" filter. It came out of the fourth feedback round (a system
 * message that the game localises was translated a second time) and skipped any text
 * that was byte for byte a vanilla value. The actual root cause of that report is
 * handled where the arguments of a {@code translatable} node are read
 * ({@code ComponentExtractor#collectArgs}: String arguments are data, a command
 * argument is not prose). What the value filter really hit was map-authored
 * <em>literal</em> text that happens to match a vanilla word - the fifteenth round
 * report is a scoreboard team prefix {@code [Guardian]} ("Guardian" is the name of a
 * vanilla mob), which stayed English while the suffix next to it was translated. A
 * literal is never localised by the game, so skipping it only left English on screen.
 */
public final class VanillaText {
	private static final Logger LOGGER = AITranslateMod.LOGGER;
	private static final String RESOURCE = "/assets/minecraft/lang/en_us.json";
	private static final int MAX_TRACKED_VALUE_LENGTH = 120;

	private static final Map<String, String> BY_KEY = new HashMap<>();
	private static final AtomicBoolean LOADED = new AtomicBoolean(false);

	private VanillaText() {
	}

	/** Starts the background load; safe to call more than once. */
	public static void load() {
		if (!LOADED.compareAndSet(false, true)) {
			return;
		}
		Thread thread = new Thread(VanillaText::read, "ai-translate-vanilla-text");
		thread.setDaemon(true);
		thread.start();
	}

	/** Loads synchronously (tests); returns true when the file was read. */
	public static boolean loadBlocking(long timeoutMs) {
		if (!LOADED.compareAndSet(false, true)) {
			return !BY_KEY.isEmpty();
		}
		Thread thread = new Thread(VanillaText::read, "ai-translate-vanilla-text");
		thread.setDaemon(true);
		thread.start();
		try {
			thread.join(Math.max(100L, timeoutMs));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (BY_KEY) {
			return !BY_KEY.isEmpty();
		}
	}

	private static void read() {
		try (InputStream stream = VanillaText.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) {
				LOGGER.warn("Vanilla language file {} not found - vanilla key lookups stay empty", RESOURCE);
				return;
			}
			JsonElement root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				return;
			}
			JsonObject object = root.getAsJsonObject();
			Map<String, String> byKey = new HashMap<>(object.size() * 2);
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				if (!entry.getValue().isJsonPrimitive()) {
					continue;
				}
				String value = entry.getValue().getAsString();
				if (value.isBlank() || value.length() > MAX_TRACKED_VALUE_LENGTH) {
					continue;
				}
				byKey.put(entry.getKey(), value);
			}
			synchronized (BY_KEY) {
				BY_KEY.clear();
				BY_KEY.putAll(byKey);
			}
			LOGGER.info("Vanilla language keys loaded ({} keys)", byKey.size());
		} catch (Exception e) {
			LOGGER.warn("Could not read the vanilla language file", e);
		}
	}

	/**
	 * The English text of a vanilla translation key, or {@code null}.
	 * <p>
	 * Used for item names: a vanilla item name is a {@code translatable} key, and the
	 * game only shows it in the target language when the client language happens to
	 * be the target language. With this lookup the key can be turned into its English
	 * text and translated like any other text (opt-in, see
	 * {@code ModConfig#translateVanillaItemNames}).
	 */
	public static String englishFor(String key) {
		if (key == null) {
			return null;
		}
		synchronized (BY_KEY) {
			return BY_KEY.get(key);
		}
	}
}
