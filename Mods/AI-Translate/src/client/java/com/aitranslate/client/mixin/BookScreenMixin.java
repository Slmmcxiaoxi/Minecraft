package com.aitranslate.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.capture.ComponentExtractor;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.BookPageContext;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.Style;

/**
 * Written book / book and quill page translation.
 * <p>
 * {@code BookViewScreen#visitText} reads the current page from its
 * {@code BookAccess}, merges it with the page text style and splits the result
 * into the lines it renders. Redirecting that merge call substitutes the page
 * component on its way into the layout, so the translated page is wrapped,
 * paginated and positioned exactly like the original one.
 * <p>
 * A redirect is used instead of a local variable modifier on purpose - the
 * official class files ship without a local variable table, which makes local
 * variable matching unreliable.
 * <p>
 * Eleventh feedback round: the hook also names the page it is translating
 * ("book page 19/21", from {@link BookPageContext}) and, when
 * {@code logBookPages} is on, logs the state of every page it sees - which is how
 * "page 19 and 20 stay in English" became visible in the log at all, since a page
 * that keeps its original text is otherwise silent by design.
 */
@Mixin(BookViewScreen.class)
public abstract class BookScreenMixin {

	@Redirect(method = "visitText", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/ComponentUtils;mergeStyles(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/Style;)Lnet/minecraft/network/chat/Component;"))
	private static Component aiTranslate$page(Component page, Style style) {
		String label = BookPageContext.label();
		Component translated = TranslationSupport.translated(page, TextType.BOOK, null, label);
		aiTranslate$logPage(page, translated, label);
		return ComponentUtils.mergeStyles(translated, style);
	}

	/**
	 * One line per page and state (original -> translated), so a page that never
	 * gets its translation is visible next to the page that does.
	 */
	private static void aiTranslate$logPage(Component page, Component translated, String label) {
		if (AITranslateModClient.config == null || !AITranslateModClient.config.logBookPages) {
			return;
		}
		boolean shown = translated != page;
		// Identity based key: the game returns the same component for the same page,
		// so this logs once per page and state instead of once per frame.
		String key = "bookpage:" + BookPageContext.page() + ":" + System.identityHashCode(page) + ":" + shown;
		com.aitranslate.client.util.DebugLog.onceLazy(key, () -> aiTranslate$describe(page, shown, label));
	}

	private static String aiTranslate$describe(Component page, boolean shown, String label) {
		List<String> texts = ComponentExtractor.uniqueTexts(page);
		int cached = 0;
		StringBuilder preview = new StringBuilder();
		for (String text : texts) {
			if (AITranslateModClient.scheduler != null
					&& AITranslateModClient.scheduler.cached(TextType.BOOK, text).isPresent()) {
				cached++;
			}
			if (preview.length() == 0 && text != null) {
				preview.append(text.replaceAll("\\s+", " "));
			}
		}
		String summary = preview.length() <= 60 ? preview.toString() : preview.substring(0, 60) + "…";
		return "[book] " + label + ": " + texts.size() + " text(s), cached=" + cached + "/" + texts.size()
				+ ", shown=" + (shown ? "translation" : "original") + ", chars="
				+ texts.stream().mapToInt(String::length).sum() + ", \"" + summary + "\"";
	}
}
