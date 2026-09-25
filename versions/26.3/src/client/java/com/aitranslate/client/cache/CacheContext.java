package com.aitranslate.client.cache;

import java.nio.file.Path;

import com.aitranslate.client.util.FileUtil;

/**
 * Identifies the isolated cache bucket: one directory per single-player world
 * or per multiplayer server address.
 */
public record CacheContext(ContextType type, String identifier) {
	public enum ContextType {
		SINGLEPLAYER,
		MULTIPLAYER
	}

	public static CacheContext singleplayer(String worldName) {
		return new CacheContext(ContextType.SINGLEPLAYER, FileUtil.sanitize(worldName));
	}

	/**
	 * Creates a single-player bucket from the save directory, which is unique even
	 * when two saves have the same display name. The level name remains a fallback
	 * for unusual server implementations that do not expose a directory.
	 */
	public static CacheContext singleplayer(Path saveDirectory, String fallbackWorldName) {
		String identifier = null;
		if (saveDirectory != null && saveDirectory.getFileName() != null) {
			identifier = saveDirectory.getFileName().toString();
		}
		if (identifier == null || identifier.isBlank()) {
			identifier = fallbackWorldName;
		}
		if (identifier == null || identifier.isBlank()) {
			identifier = "unknown_world";
		}
		return singleplayer(identifier);
	}

	public static CacheContext multiplayer(String address) {
		return new CacheContext(ContextType.MULTIPLAYER, FileUtil.sanitize(address));
	}

	/** {@code singleplayer/<world>} or {@code multiplayer/<address>}. */
	public String directoryName() {
		return type.name().toLowerCase(java.util.Locale.ROOT) + "/" + identifier;
	}

	@Override
	public String toString() {
		return type == ContextType.SINGLEPLAYER
				? "singleplayer:" + identifier
				: "multiplayer:" + identifier;
	}
}
