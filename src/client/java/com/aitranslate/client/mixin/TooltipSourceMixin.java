package com.aitranslate.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.display.TooltipLines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Item tooltip lines, translated at their source (see {@link TooltipLines}).
 * <p>
 * {@code Screen#getTooltipFromItem} is the single vanilla entry point for item
 * tooltips: the inventory, chest, creative and hotbar paths all build their lines
 * here, and whatever renders them afterwards - vanilla or a UI replacement such as
 * ModernUI - receives the translated list.
 */
@Mixin(Screen.class)
public abstract class TooltipSourceMixin {

	@Inject(method = "getTooltipFromItem", at = @At("RETURN"), cancellable = true)
	private static void aiTranslate$tooltipFromItem(Minecraft client, ItemStack stack,
			CallbackInfoReturnable<List<Component>> callback) {
		List<Component> lines = callback.getReturnValue();
		List<Component> translated = TooltipLines.translated(lines);
		if (translated != lines) {
			callback.setReturnValue(translated);
		}
	}
}
