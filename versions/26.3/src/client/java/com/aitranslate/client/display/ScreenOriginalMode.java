package com.aitranslate.client.display;

import java.util.EnumSet;
import java.util.Set;

import com.aitranslate.client.capture.TextType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The in-screen "show the original" switch (thirteenth feedback round).
 * <p>
 * Reported problem: the master switch key does not reach the player while a book,
 * a book and quill, a dialog or the advancement screen is open - they had to close
 * the screen, press the key and open it again. This class remembers, per open
 * screen, that the player wants the original text, and every screen-scoped source
 * obeys it.
 * <p>
 * Three rules from the feedback document are built in:
 * <ul>
 * <li>it only affects the screen it was pressed in ({@link #hides(TextType)} only
 * answers for screen-scoped sources, and the flag is dropped as soon as another
 * screen is opened);</li>
 * <li>the master switch wins: with translation off everywhere, the in-screen switch
 * does nothing and says so;</li>
 * <li>nothing is thrown away - the translations stay in the cache, so switching back
 * is instant and costs no request.</li>
 * </ul>
 */
public final class ScreenOriginalMode {
	/**
	 * The text sources that belong to the open screen. The HUD (boss bar, scoreboard,
	 * titles) and the world (signs, name tags, text displays) are deliberately not in
	 * here: the switch is about the screen the player is looking at, not about the
	 * whole game. Chat is in here because the chat screen <em>is</em> one of the
	 * screens with a button (fourteenth feedback round), and the lines behind it are
	 * what that button switches.
	 */
	private static final Set<TextType> SCREEN_SOURCES = EnumSet.of(TextType.BOOK, TextType.DIALOG,
			TextType.ADVANCEMENT, TextType.CONTAINER, TextType.TOOLTIP, TextType.ITEM, TextType.CHAT);

	/** The screen whose text is currently shown as the original, or {@code null}. */
	private static volatile Screen originalScreen;
	/** Screen explicitly forced to translations despite an "original by default" option. */
	private static volatile Screen translatedScreen;

	private ScreenOriginalMode() {
	}

	/**
	 * The screens the switch is offered on. This is an explicit allow-list: a new
	 * screen gets no button until it is deliberately added here.
	 * <p>
	 * The reported problem was that the button also appeared on the title screen, the
	 * single-player world list, the multiplayer list and Mod Menu. The cause was the
	 * fallback rule "any screen with a text input gets the button": the world list has a
	 * search box, and Mod Menu's list does too - so a purely input-driven rule reaches
	 * screens where there is no game text to switch at all.
	 * <p>
	 * The rules now, in order:
	 * <ol>
	 * <li>chat;</li>
	 * <li>read-only books and book-and-quill editing;</li>
	 * <li>dialogs, containers and advancements.</li>
	 * </ol>
	 */
	public static boolean supports(Screen screen) {
		return screen instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen
				|| screen instanceof net.minecraft.client.gui.screens.inventory.BookEditScreen
				|| screen instanceof net.minecraft.client.gui.screens.dialog.DialogScreen<?>
				|| screen instanceof net.minecraft.client.gui.screens.advancements.AdvancementsScreen
				|| screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
				|| screen instanceof net.minecraft.client.gui.screens.ChatScreen;
	}

	/** True while a screen is showing originals instead of translations. */
	public static boolean isActive() {
		return isShowingOriginal(Minecraft.getInstance().gui.screen());
	}

	/** Effective state, including the book-and-quill's configurable default. */
	public static boolean isShowingOriginal(Screen screen) {
		if (screen == null) {
			return false;
		}
		if (originalScreen == screen) {
			return true;
		}
		var config = com.aitranslate.client.AITranslateModClient.config;
		return screen instanceof net.minecraft.client.gui.screens.inventory.BookEditScreen
				&& translatedScreen != screen
				&& (config == null || !config.translateBookEditPage);
	}

	/** True when the current screen manually overrides an original-by-default option. */
	public static boolean forcesTranslation(TextType type) {
		return type == TextType.BOOK && translatedScreen != null
				&& translatedScreen == Minecraft.getInstance().gui.screen();
	}

	/** True when this text type has to keep its original text right now. */
	public static boolean hides(TextType type) {
		return SCREEN_SOURCES.contains(type) && isActive();
	}

	/** True when this screen is the one showing originals. */
	public static boolean isActiveFor(Screen screen) {
		return isShowingOriginal(screen);
	}

	/**
	 * Switches the text of {@code screen} between the translation and the original,
	 * reports it on the action bar and tells the player when the master switch makes
	 * the switch pointless.
	 *
	 * @return the new state: {@code true} means "originals are shown now"
	 */
	public static boolean toggle(Screen screen) {
		if (screen == null) {
			return false;
		}
		boolean nowOriginal = !isShowingOriginal(screen);
		originalScreen = nowOriginal ? screen : null;
		translatedScreen = nowOriginal ? null : screen;
		// Caches that bake their text follow the generation counter and switch on the
		// very next frame. Widgets that baked their text into their construction - the
		// dialog title is a StringWidget built with the translated text, the
		// advancement screen builds every label once - need a real rebuild, which is
		// what the forced refresh does on the next tick.
		//
		// Fourteenth feedback round: without the force, the dialog title kept whatever
		// was baked in when the screen was last rebuilt, so switching to the original
		// and back showed the original title while the body switched back - the
		// "title does not follow the switch" report.
		TranslationSupport.bumpGeneration();
		TranslationSupport.clearRenderMemo();
		RefreshCoordinator.requestFullRefresh();
		feedback(nowOriginal);
		return nowOriginal;
	}

	private static void feedback(boolean nowOriginal) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.gui == null) {
			return;
		}
		var config = com.aitranslate.client.AITranslateModClient.config;
		if (config != null && config.translateEnabled == false) {
			// The master switch is off: there is nothing to switch away from, and the
			// player should know why nothing happens.
			client.gui.hud.setOverlayMessage(
					net.minecraft.network.chat.Component.literal("[AT] 翻译总开关已关闭——本界面没有译文可切换"), false);
			return;
		}
		client.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal(nowOriginal
				? "[AT] 本界面显示原文（再按一次切换回译文）"
				: "[AT] 本界面显示译文"), false);
	}

	/**
	 * Forgets the choice when the player leaves the screen it was made in, so opening
	 * the next book starts with translations again.
	 */
	public static void tick(Minecraft client) {
		Screen current = client == null ? null : client.gui.screen();
		Screen original = originalScreen;
		Screen translated = translatedScreen;
		if (original != null && original != current || translated != null && translated != current) {
			originalScreen = null;
			translatedScreen = null;
			TranslationSupport.bumpGeneration();
			TranslationSupport.clearRenderMemo();
		}
	}

	/** Drops the choice (used by the master switch and the config reset). */
	public static void clear() {
		if (originalScreen != null || translatedScreen != null) {
			originalScreen = null;
			translatedScreen = null;
			TranslationSupport.bumpGeneration();
		}
	}
}
