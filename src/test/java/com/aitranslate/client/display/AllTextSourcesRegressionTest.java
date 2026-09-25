package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Guards every render hook named in the severe-regression acceptance checklist. */
class AllTextSourcesRegressionTest {
	private static final Path MIXINS = Path.of("src/client/java/com/aitranslate/client/mixin");

	@Test
	void everyTextSourceStillReachesTheSharedTranslationPipeline() throws IOException {
		Map<String, String> hooks = Map.ofEntries(
				Map.entry("ChatHudMixin.java", "TranslationSupport.translatedChat"),
				Map.entry("ItemNameMixin.java", "TranslationSupport.translated"),
				Map.entry("ItemStackTooltipMixin.java", "TooltipLines.translated"),
				Map.entry("SignTextMixin.java", "TranslationSupport.translatedBlockText"),
				Map.entry("BookScreenMixin.java", "TranslationSupport.translated"),
				Map.entry("BookEditScreenMixin.java", "BookEditState"),
				Map.entry("TitlePacketMixin.java", "TranslationSupport.translated"),
				Map.entry("OverlayMessageMixin.java", "TranslationSupport.translated"),
				Map.entry("BossBarMixin.java", "TranslationSupport.translated"),
				Map.entry("ScoreboardMixin.java", "TranslationSupport.translated"),
				Map.entry("EntityNameMixin.java", "TranslationSupport.translated"),
				Map.entry("DialogScreenMixin.java", "TranslationSupport.translated"),
				Map.entry("DialogButtonMixin.java", "TranslationSupport.translated"),
				Map.entry("AdvancementMixin.java", "TranslationSupport.translated"),
				Map.entry("ContainerTitleMixin.java", "TranslationSupport.translated"),
				Map.entry("TextDisplayMixin.java", "TranslationSupport.translated"),
				Map.entry("PlayerTabOverlayMixin.java", "TranslationSupport.translated"));

		for (var hook : hooks.entrySet()) {
			String source = Files.readString(MIXINS.resolve(hook.getKey()));
			assertTrue(source.contains(hook.getValue()), hook.getKey() + " no longer reaches translation");
		}
	}

	@Test
	void chatCommandKindsAndTeamDecorationsRemainCovered() throws IOException {
		String kinds = Files.readString(Path.of("src/client/java/com/aitranslate/client/capture/ChatKind.java"));
		for (String value : new String[] { "CHAT", "EMOTE", "ANNOUNCEMENT", "PRIVATE", "SCRIPT",
				"chat.type.team.text", "commands.message.display.", "death." }) {
			assertTrue(kinds.contains(value), "missing chat/team route " + value);
		}
		String extractor = Files.readString(
				Path.of("src/client/java/com/aitranslate/client/capture/ComponentExtractor.java"));
		assertTrue(extractor.contains("rebuildDecoratedPlayer"));
	}
}
