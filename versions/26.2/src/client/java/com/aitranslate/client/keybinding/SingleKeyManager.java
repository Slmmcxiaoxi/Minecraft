package com.aitranslate.client.keybinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.aitranslate.AITranslateMod;
import net.minecraft.client.Minecraft;

/** Polls single-key bindings and invokes each action on the rising edge only. */
public class SingleKeyManager {
	private static final java.util.Set<Integer> HELD = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final List<Binding> bindings = new ArrayList<>();
	private final Map<String, Boolean> lastState = new HashMap<>();

	public record Binding(String id, Supplier<SingleKeyBinding> key, Runnable action) {
	}

	public static void onKeyState(int keyCode, boolean down) {
		if (down) {
			HELD.add(keyCode);
		} else {
			HELD.remove(keyCode);
		}
	}

	public void add(String id, Supplier<SingleKeyBinding> key, Runnable action) {
		bindings.add(new Binding(id, key, action));
	}

	/** Called once at the end of each client tick. */
	public void tick(Minecraft client) {
		if (client == null || client.getWindow() == null) {
			return;
		}
		if (client.gui.screen() != null) {
			lastState.replaceAll((id, state) -> Boolean.FALSE);
			return;
		}
		long window = client.getWindow().handle();
		java.util.Set<Integer> consumedKeys = new java.util.HashSet<>();
		for (Binding binding : bindings) {
			SingleKeyBinding key = binding.key().get();
			boolean pressed = key != null && key.isPressed(window, HELD);
			boolean previous = lastState.getOrDefault(binding.id(), Boolean.FALSE);
			lastState.put(binding.id(), pressed);
			if (pressed && !previous && consumedKeys.add(key.keyCode())) {
				AITranslateMod.LOGGER.debug("Key {} triggered ({})", key.describe(), binding.id());
				binding.action().run();
			}
		}
	}
}
