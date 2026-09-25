package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Guards the eighteenth-round explicit screen allow-list. */
class ScreenWhitelistTest {
	@Test
	void buttonSupportHasNoWorldOrTextInputFallback() throws Exception {
		Path source = Path.of("src", "client", "java", "com", "aitranslate", "client", "display",
				"ScreenOriginalMode.java");
		assumeTrue(Files.isRegularFile(source));
		String text = Files.readString(source);
		assertTrue(text.contains("instanceof net.minecraft.client.gui.screens.ChatScreen"));
		assertTrue(text.contains("instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen"));
		assertTrue(text.contains("instanceof net.minecraft.client.gui.screens.dialog.DialogScreen"));
		assertFalse(text.contains("ScreenTextInputs.hasTextInput(screen)"));
		assertFalse(text.contains("client.level != null"));
		assertFalse(text.contains("return name.startsWith"));
	}
}
