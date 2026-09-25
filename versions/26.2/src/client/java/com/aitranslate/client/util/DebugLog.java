package com.aitranslate.client.util;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aitranslate.AITranslateMod;

/**
 * Rate limited debug logging.
 * <p>
 * Render hooks fire every frame, so logging straight from them floods the log
 * file (a five minute session produced almost 3 MB). Each distinct message is
 * logged once per session instead, which is exactly what is needed to check
 * whether a text source is hooked.
 */
public final class DebugLog {
	private static final Set<String> SEEN = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private DebugLog() {
	}

	public static void once(String key, String format, Object... args) {
		if (!SEEN.add(key)) {
			return;
		}
		AITranslateMod.LOGGER.info(format, args);
	}

	/**
	 * Same, but the message is only built when the key is new.
	 * <p>
	 * Render hooks fire every frame, and some diagnostics (walking the leaves of a
	 * book page, for instance) are too expensive to prepare only to throw away.
	 */
	public static void onceLazy(String key, java.util.function.Supplier<String> message) {
		if (!SEEN.add(key)) {
			return;
		}
		AITranslateMod.LOGGER.info(message.get());
	}

	public static void reset() {
		SEEN.clear();
	}
}
