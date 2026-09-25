package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Release-surface checks for features explicitly removed before publishing. */
class PreReleaseWiringTest {
	private static String source(String relative) throws Exception {
		return Files.readString(Path.of("src/client/java").resolve(relative));
	}

	@Test
	void currentCacheKeepsOnlyExportImportAndClear() throws Exception {
		String config = source("com/aitranslate/client/config/ConfigScreen.java");
		assertFalse(config.contains("管理当前缓存"));
		assertFalse(Files.exists(Path.of("src/client/java/com/aitranslate/client/config/CacheScreen.java")));
		assertTrue(config.contains("Component.literal(\"导出\"), ConfigScreen::exportCurrentCache"));
		assertTrue(config.contains("Component.literal(\"导入\"), ConfigScreen::importCurrentCache"));
		assertTrue(config.contains("Component.literal(\"清理\"), ConfigScreen::clearCurrent"));
	}

	@Test
	void buttonPositionScreenHasNoResetControl() throws Exception {
		String position = source("com/aitranslate/client/display/ButtonPositionScreen.java");
		assertFalse(position.contains("Component.literal(\"重置\")"));
		assertFalse(position.contains("resetPosition("));
		assertTrue(position.contains("Component.literal(\"保存\")"));
		assertTrue(position.contains("Component.literal(\"取消\")"));
	}

	@Test
	void developmentMockProviderIsNotPackaged() throws Exception {
		assertFalse(Files.exists(Path.of("src/client/java/com/aitranslate/client/provider/MockProvider.java")));
		assertFalse(source("com/aitranslate/client/config/ModConfig.java").contains("useMockProvider"));
	}

	@Test
	void duplicateWorldKeysAreConsumedByOnlyOneActionPerPress() throws Exception {
		String manager = source("com/aitranslate/client/keybinding/SingleKeyManager.java");
		assertTrue(manager.contains("consumedKeys.add(key.keyCode())"));
	}

	@Test
	void firstLaunchNormalizesConfigAndCreatesStorageRoots() throws Exception {
		String config = source("com/aitranslate/client/config/ModConfig.java");
		String client = source("com/aitranslate/client/AITranslateModClient.java");
		assertTrue(config.contains("config.fixup();\n\t\t\tconfig.save();"));
		assertTrue(client.contains("ensureStorageDirectories();"));
		assertTrue(client.contains("Files.createDirectories(cacheManager.getRootDir())"));
		assertTrue(client.contains("Files.createDirectories(dictionaryRootDir())"));
	}
}
