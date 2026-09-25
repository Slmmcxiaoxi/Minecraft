package com.aitranslate.client.keybinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.platform.InputConstants;

import com.aitranslate.client.config.ModConfig;

class SingleKeyBindingTest {
	@Test
	void documentedDefaultsAreSingleKeys() {
		ModConfig config = new ModConfig();
		assertEquals(InputConstants.KEY_O, config.openConfigKey);
		assertEquals(InputConstants.KEY_U, config.toggleTranslateKey);
		assertEquals(InputConstants.KEY_R, config.refreshTranslateKey);
		assertEquals(InputConstants.KEY_R, config.toggleOriginalKey);
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
		SingleKeyBinding binding = new SingleKeyBinding(InputConstants.KEY_U);
		assertTrue(binding.isPressed(0L, java.util.Set.of(InputConstants.KEY_U)));
		assertFalse(binding.isPressed(0L, java.util.Set.of(InputConstants.KEY_LSHIFT)));
		assertEquals("U", binding.describe());
		assertEquals("F5", new SingleKeyBinding(InputConstants.KEY_F5).describe());
	}
}
