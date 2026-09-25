package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Structural guards for hooks whose behavior depends on Minecraft render state. */
class EighteenthRoundWiringTest {
	private static final Path MIXINS = Path.of("src", "client", "java", "com", "aitranslate", "client", "mixin");

	@Test
	void entityNamesProtectPlayerIdentityButTranslateTeamDecorations() throws Exception {
		String source = read("EntityNameMixin.java");
		assertTrue(source.contains("state.entityType == EntityTypes.PLAYER"));
		assertTrue(source.contains("ComponentExtractor.playerIdentity(component)"));
		assertTrue(source.contains("protectPlayerName(identity)"));
		assertTrue(source.contains("TranslationSupport.translated(component, TextType.ENTITY_NAME, priority)"));
	}

	@Test
	void dialogButtonsTranslateBeforeButtonConstruction() throws Exception {
		String source = read("DialogButtonMixin.java");
		assertTrue(source.contains("@Mixin(DialogControlSet.class)"));
		assertTrue(source.contains("Button.builder(TranslationSupport.translated(label, TextType.DIALOG)"));
	}

	@Test
	void signsUseOneWholeBlockCacheKeyAndSkipEditing() throws Exception {
		String source = read("SignTextMixin.java");
		assertTrue(source.contains("SignTranslationLayout.requestText(originals)"));
		assertTrue(source.contains("index == 0 && !aiTranslate$editingSign"));
		assertTrue(source.contains("translatedBlockText(aiTranslate$wholeSignKey"));
		assertTrue(source.contains("endsWith(\"SignEditScreen\")"));
	}

	@Test
	void containerTitlesPreserveVanillaAlignmentAnchor() throws Exception {
		String source = read("ContainerTitleMixin.java");
		assertTrue(source.contains("translatedTitleX"));
		assertTrue(source.contains("return originalX"));
	}

	private static String read(String name) throws Exception {
		Path source = MIXINS.resolve(name);
		assumeTrue(Files.isRegularFile(source));
		return Files.readString(source);
	}
}
