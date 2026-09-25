package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Sidebar scoreboard translation.
 * <p>
 * {@code Gui#displayScoreboardSidebar} draws the objective display name, the row
 * names and the scores through the same extractor call. Redirecting that call
 * translates only what is rendered - the scoreboard data, objectives, teams and
 * internal ids stay untouched.
 * <p>
 * Scope rules: the objective <em>display name</em> and row display texts are
 * translated, internal names are never touched (they are not rendered at all),
 * and real player names are skipped by {@code TextDetector} so that a player
 * list on the sidebar stays readable.
 */
@Mixin(Gui.class)
public abstract class ScoreboardMixin {

	@Redirect(method = "displayScoreboardSidebar", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"))
	private void aiTranslate$sidebarText(GuiGraphicsExtractor extractor, Font font, Component component, int x, int y,
			int color, boolean shadow) {
		Component translated = TranslationSupport.translated(component, TextType.SCOREBOARD);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"scoreboard:" + component.getString() + "->" + translated.getString(),
					"[hook] SCOREBOARD '{}' -> '{}'", component.getString(), translated.getString());
		}
		extractor.text(font, translated, x, y, color, shadow);
	}

	/**
	 * Layout of the sidebar (third feedback round, "不同语言长度差异").
	 * <p>
	 * {@code displayScoreboardSidebar} measures the objective display name and
	 * every row name <em>before</em> it draws them, and the background box and the
	 * score column are positioned from those measurements. Replacing only the drawn
	 * component would therefore have drawn e.g. a four character Chinese row into a
	 * box measured for the English original.
	 * <p>
	 * Measuring the translation instead keeps box, row centring and the score
	 * column consistent with what is actually drawn. Both this hook and the draw
	 * hook above go through {@link TranslationSupport#translated}, which memoises
	 * per component and generation, so they always agree on the same text.
	 */
	@Redirect(method = "displayScoreboardSidebar", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"))
	private int aiTranslate$sidebarWidth(Font font, net.minecraft.network.chat.FormattedText text) {
		if (text instanceof Component component) {
			return font.width((net.minecraft.network.chat.FormattedText)
					TranslationSupport.translated(component, TextType.SCOREBOARD));
		}
		return font.width(text);
	}
}
