package com.aitranslate.client.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;

/** Safe one-time moves for storage paths used by older releases. */
public final class StorageMigration {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	private StorageMigration() {
	}

	public static void migrateLegacyDictionaries(Path legacyCache, Path dictionaryRoot) {
		if (legacyCache == null || dictionaryRoot == null) return;
		Path oldRoot = legacyCache.toAbsolutePath().normalize();
		Path newRoot = dictionaryRoot.toAbsolutePath().normalize();
		if (oldRoot.equals(newRoot) || !Files.isDirectory(oldRoot)) return;
		try (var files = Files.walk(oldRoot)) {
			for (Path source : files.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equalsIgnoreCase("dictionary.json")).toList()) {
				moveVerified(source, newRoot.resolve(oldRoot.relativize(source)));
			}
		} catch (IOException e) {
			LOGGER.warn("Could not scan legacy dictionaries under {}", oldRoot, e);
		}
		Path oldExports = oldRoot.resolve("dictionary-exports");
		if (Files.isDirectory(oldExports)) {
			try (var files = Files.walk(oldExports)) {
				for (Path source : files.filter(Files::isRegularFile).toList()) {
					moveVerified(source, newRoot.resolve("exports").resolve(oldExports.relativize(source)));
				}
			} catch (IOException e) {
				LOGGER.warn("Could not migrate dictionary exports from {}", oldExports, e);
			}
		}
	}

	private static void moveVerified(Path source, Path target) {
		try {
			if (Files.exists(target)) {
				LOGGER.info("Dictionary migration kept existing target {}; legacy source remains at {}", target, source);
				return;
			}
			Files.createDirectories(target.getParent());
			Path temporary = target.resolveSibling(target.getFileName() + ".migrating");
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			if (Files.size(source) != Files.size(temporary)) {
				Files.deleteIfExists(temporary);
				throw new IOException("size verification failed");
			}
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temporary, target);
			}
			Files.delete(source);
			LOGGER.info("Migrated dictionary {} -> {}", source, target);
		} catch (IOException e) {
			LOGGER.warn("Could not migrate dictionary {} -> {}; source was preserved", source, target, e);
		}
	}
}
