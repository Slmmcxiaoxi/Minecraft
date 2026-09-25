package com.aitranslate.client.keybinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import com.aitranslate.client.config.ModConfig;

class SingleKeyBindingTest {
	@Test
	void documentedDefaultsAreSingleKeys() {
		ModConfig config = new ModConfig();
		assertEquals(GLFW.GLFW_KEY_O, config.openConfigKey);
		assertEquals(GLFW.GLFW_KEY_U, config.toggleTranslateKey);
		assertEquals(GLFW.GLFW_KEY_R, config.refreshTranslateKey);
		assertEquals(GLFW.GLFW_KEY_R, config.toggleOriginalKey);
	}

	@Test
	void unboundKeyNeverTriggers() {
		SingleKeyBinding unbound = SingleKeyBinding.unbound();
		assertFalse(unbound.isBound());
		assertFalse(unbound.isPressed(0L, java.util.Set.of()));
		assertEquals("未绑定", unbound.describe());
	}

	@Test
	void oneHeldKeyTriggersAndHasAReadableName() {
		SingleKeyBinding binding = new SingleKeyBinding(GLFW.GLFW_KEY_U);
		assertTrue(binding.isPressed(0L, java.util.Set.of(GLFW.GLFW_KEY_U)));
		assertFalse(binding.isPressed(0L, java.util.Set.of(GLFW.GLFW_KEY_LEFT_SHIFT)));
		assertEquals("U", binding.describe());
		assertEquals("F5", new SingleKeyBinding(GLFW.GLFW_KEY_F5).describe());
	}
}
