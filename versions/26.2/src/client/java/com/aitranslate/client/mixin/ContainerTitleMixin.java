package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

/**
 * Container title translation.
 * <p>
 * Chests, barrels, hoppers, shulker boxes, furnaces and the like can be renamed
 * with an anvil (or by a map author). The name lives in the block entity's
 * {@code customName} and reaches the screen as the menu title component; the
 * default names are {@code translatable} keys and are skipped by the extractor,
 * so only hardcoded custom names are translated.
 * <p>
 * {@code AbstractContainerScreen#extractLabels} draws the title and the player
 * inventory label through the same extractor call. Redirecting it keeps the
 * layout, coordinates and colour untouched - only the component changes.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerTitleMixin {
	@Shadow
	@Final
	protected int imageWidth;

	@Redirect(method = "extractLabels(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
					ordinal = 0))
	private void aiTranslate$containerTitle(GuiGraphicsExtractor extractor, Font font, Component component, int x,
			int y, int color, boolean shadow) {
		Component translated = TranslationSupport.translated(component, TextType.CONTAINER);
		int translatedX = translatedTitleX(font, component, translated, x, imageWidth);
		extractor.text(font, translated, translatedX, y, color, shadow);
	}

	/**
	 * Vanilla stores the already measured title coordinate in {@code titleLabelX}.
	 * Furnace-like screens calculate it from the source title width during init,
	 * while ordinary containers keep the fixed left aligned coordinate (normally
	 * eight pixels). Replacing the component but reusing that measured coordinate
	 * shifts a centred translation by half of the width difference.
	 *
	 * Detect the alignment represented by the original coordinate: only a title
	 * whose coordinate is exactly vanilla's centred coordinate is measured again.
	 * Fixed/left aligned layouts retain their coordinate unchanged. This preserves
	 * each screen's own layout instead of forcing every container to the centre.
	 */
	private static int translatedTitleX(Font font, Component original, Component translated, int originalX, int imageWidth) {
		if (translated == original || translated.getString().equals(original.getString())) {
			return originalX;
		}
		int originalCenteredX = (imageWidth - font.width(original)) / 2;
		if (originalX == originalCenteredX) {
			return (imageWidth - font.width(translated)) / 2;
		}
		return originalX;
	}
}
