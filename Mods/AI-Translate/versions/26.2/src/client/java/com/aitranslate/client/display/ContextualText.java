package com.aitranslate.client.display;

import com.aitranslate.client.capture.TextType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;

/**
 * The screen-scoped text hook.
 * <p>
 * Some render calls draw text that belongs to a screen the player has open but do
 * not say which one - {@code GuiGraphicsExtractor#text} and a widget's label both
 * take a plain {@link Component}. This class maps the open screen to the text
 * source, so dialog titles, book widgets and advancement labels are translated
 * while unrelated GUI literals (menus, the debug overlay, other mods' screens) are
 * left alone.
 * <p>
 * Both hooks used to carry their own copy of this mapping (twelfth feedback round:
 * moved here), which meant a new screen could be added to one of them and
 * forgotten in the other.
 */
public final class ContextualText {

	private ContextualText() {
	}

	/**
	 * The component to draw instead of {@code component}, or {@code component}
	 * itself when the open screen is not one of the mod's text sources.
	 * <p>
	 * Components built by this mod are never translated twice.
	 */
	public static Component forCurrentScreen(Component component) {
		if (component == null || TranslationSupport.isDisplay(component)) {
			return component;
		}
		Screen screen = Minecraft.getInstance().gui.screen();
		if (screen instanceof DialogScreen<?>) {
			return TranslationSupport.translated(component, TextType.DIALOG);
		}
		if (screen instanceof BookViewScreen) {
			return TranslationSupport.translated(component, TextType.BOOK);
		}
		if (screen instanceof AdvancementsScreen) {
			// The window title is a vanilla translatable ("Advancements") and is
			// skipped by the extractor; a map's tab names and hardcoded screen text
			// are not (fifth feedback round: the advancement GUI title was
			// untranslated).
			return TranslationSupport.translated(component, TextType.ADVANCEMENT);
		}
		return component;
	}
}
