package com.aitranslate.client.display;

import java.util.ArrayList;
import java.util.List;

import com.aitranslate.client.capture.TextType;
import net.minecraft.network.chat.Component;

/**
 * Item tooltip lines, translated where they are produced.
 * <p>
 * The earlier hooks replaced the line list on its way into
 * {@code GuiGraphicsExtractor#setTooltipForNextFrame(Font, List, …)}. That works
 * with vanilla rendering but <strong>not with ModernUI</strong>: ModernUI injects
 * into that very method, renders the tooltip with its own layout engine and skips
 * the vanilla body, so a list substituted there never reaches the screen. That is
 * why the inventory tooltip stayed untranslated for a user who has ModernUI
 * installed (it is in their instance, together with ForgeConfigAPIPort).
 * <p>
 * Translating the lines where they are <em>built</em> is renderer independent:
 * {@code Screen#getTooltipFromItem} is called by every item tooltip path
 * ({@code AbstractContainerScreen#extractTooltip}, {@code ItemDisplayWidget},
 * {@code setTooltipForNextFrame(Font, ItemStack, …)}), and the list it returns is a
 * fresh list built for this frame - the item stack and its data are never touched.
 */
public final class TooltipLines {

	private TooltipLines() {
	}

	/** Translates the lines; returns the input list itself when nothing changed. */
	public static List<Component> translated(List<Component> lines) {
		if (lines == null || lines.isEmpty()) {
			return lines;
		}
		boolean debug = com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog;
		if (debug) {
			// Logged for every item tooltip (translated or not): the fifth feedback round
			// reported untranslated tooltips, and this line says whether the path is even
			// reached and what the client language already shows.
			com.aitranslate.client.util.DebugLog.once("tooltip-src-any:" + lines.get(0).getString(),
					"[tooltip-source] {} line(s), first='{}' ({})",
					lines.size(), lines.get(0).getString(),
					lines.get(0).getContents().getClass().getSimpleName());
		}
		if (!TranslationSupport.isActive(TextType.TOOLTIP)) {
			return lines;
		}
		List<Component> result = null;
		for (int i = 0; i < lines.size(); i++) {
			Component line = lines.get(i);
			Component translated = TranslationSupport.translated(line, TextType.TOOLTIP);
			if (translated != line) {
				if (result == null) {
					result = new ArrayList<>(lines);
				}
				result.set(i, translated);
			}
		}
		if (result != null) {
			com.aitranslate.client.util.DebugLog.once("tooltip-source:" + lines.get(0).getString(),
					"[hook] TOOLTIP_SOURCE lines={} -> {}", result.size(),
					result.stream().map(Component::getString).toList());
		}
		return result == null ? lines : result;
	}
}
