package com.aitranslate.client.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Item name and lore translation.
 * <p>
 * In 26.1 tooltips are queued on the render-state extractor: the tooltip lines
 * are still {@link Component}s when they are handed to
 * {@code setTooltipForNextFrame} and only afterwards flattened into visual order
 * lines. This mixin replaces those component arguments on their way into the
 * renderer - neither the item stack nor its data components are ever touched.
 * <p>
 * Vanilla language keys ({@code translatable} nodes) are ignored by the
 * extractor, so only custom names, lore and hardcoded text are translated.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class TooltipMixin {

	/** Item tooltips: {@code setTooltipForNextFrame(font, List<Component>, ...)}. */
	@ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
			at = @At("HEAD"), argsOnly = true)
	private List<Component> aiTranslate$tooltipLines(List<Component> lines) {
		aiTranslate$diagnose("5-arg", lines);
		return aiTranslate$translateLines(lines);
	}

	/** Item tooltips carrying a tooltip style id. */
	@ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
			at = @At("HEAD"), argsOnly = true)
	private List<Component> aiTranslate$tooltipLinesStyled(List<Component> lines) {
		aiTranslate$diagnose("6-arg", lines);
		return aiTranslate$translateLines(lines);
	}

	/**
	 * Diagnostic for the repeated "item tooltips are not translated" report: with
	 * {@code debugLog} on, every tooltip that reaches the renderer is logged with its
	 * node types, translated or not. The log then says which path the screen uses and
	 * whether the text was a literal (translatable) or a vanilla translatable key
	 * (skipped by design).
	 */
	private static void aiTranslate$diagnose(String overload, List<Component> lines) {
		if (lines == null || lines.isEmpty() || com.aitranslate.client.AITranslateModClient.config == null
				|| !com.aitranslate.client.AITranslateModClient.config.debugLog) {
			return;
		}
		StringBuilder dump = new StringBuilder();
		for (Component line : lines) {
			if (dump.length() > 0) {
				dump.append(" | ");
			}
			String kind = line.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents
					? "literal"
					: line.getContents().getClass().getSimpleName();
			dump.append(kind).append(':').append(line.getString());
		}
		com.aitranslate.client.util.DebugLog.once("tooltipdiag:" + overload + ":" + dump,
				"[tooltip] {} ({} line(s)) -> {}", overload, lines.size(), dump);
	}

	/** Single component tooltips: {@code setTooltipForNextFrame(font, Component, x, y)}. */
	@ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;II)V",
			at = @At("HEAD"), argsOnly = true)
	private Component aiTranslate$tooltipComponent(Component component) {
		return TranslationSupport.translated(component, TextType.TOOLTIP);
	}

	/** Single component tooltips without an explicit font. */
	@ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/network/chat/Component;II)V",
			at = @At("HEAD"), argsOnly = true)
	private Component aiTranslate$tooltipComponentSimple(Component component) {
		return TranslationSupport.translated(component, TextType.TOOLTIP);
	}

	/** Single component tooltips with a tooltip style id. */
	@ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IILnet/minecraft/resources/Identifier;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component aiTranslate$tooltipComponentStyled(Component component) {
		return TranslationSupport.translated(component, TextType.TOOLTIP);
	}

	/**
	 * Component tooltips without a tooltip image. Used by
	 * {@code HoverEvent.ShowEntity}, the recipe book, the enchanting table and the
	 * creative ghost slots - all of which used to stay untranslated because they
	 * never reach the {@code setTooltipForNextFrame} overloads.
	 */
	@ModifyVariable(method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V",
			at = @At("HEAD"), argsOnly = true)
	private List<Component> aiTranslate$componentTooltipLines(List<Component> lines) {
		return aiTranslate$translateLines(lines);
	}

	@ModifyVariable(method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",
			at = @At("HEAD"), argsOnly = true)
	private List<Component> aiTranslate$componentTooltipLinesStyled(List<Component> lines) {
		return aiTranslate$translateLines(lines);
	}

	/** Replaces every tooltip line by its translation, keeping the line layout. */
	private static List<Component> aiTranslate$translateLines(List<Component> lines) {
		if (lines == null || lines.isEmpty() || !TranslationSupport.isActive(TextType.TOOLTIP)) {
			return lines;
		}
		List<Component> result = new ArrayList<>(lines.size());
		boolean changed = false;
		for (Component line : lines) {
			Component translated = TranslationSupport.translated(line, TextType.TOOLTIP);
			if (translated != line) {
				changed = true;
			}
			result.add(translated);
		}
		if (changed && com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once("tooltip:" + lines.get(0).getString(),
					"[hook] TOOLTIP lines={} -> {}", lines.size(),
					result.stream().map(Component::getString).toList());
		}
		return changed ? result : lines;
	}
}
