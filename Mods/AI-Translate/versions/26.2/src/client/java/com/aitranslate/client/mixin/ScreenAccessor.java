package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;

/**
 * Exposes {@link Screen#addRenderableWidget} so that the config screen can add
 * its own action buttons (cache management, key rebinding) next to the Cloth
 * Config entries, and {@code rebuildWidgets} so a screen that bakes its text into
 * its widgets (the advancement screen) can pick up a late translation. Read-only
 * accessor - no game behaviour is changed.
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
	@Invoker("addRenderableWidget")
	<T extends GuiEventListener & Renderable & NarratableEntry> T aiTranslate$addRenderableWidget(T widget);

	/** Recreates the screen's widgets (used by the advancement screen refresh). */
	@Invoker("rebuildWidgets")
	void aiTranslate$rebuildWidgets();

	/**
	 * Takes a widget out of the screen again (used when the in-screen translation
	 * button is switched off in the config, fifteenth feedback round). Without this
	 * the button would stay on the screen until it was closed and reopened.
	 */
	@Invoker("removeWidget")
	void aiTranslate$removeWidget(GuiEventListener widget);
}
