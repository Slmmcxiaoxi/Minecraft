package com.aitranslate.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.display.TooltipLines;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Container screens can add tooltip lines of their own - the creative inventory
 * prepends its tab names to the item lines - so the override is covered as well
 * (see {@link TooltipLines} for why the source is the right place to hook).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerTooltipSourceMixin {

	@Inject(method = "getTooltipFromContainerItem", at = @At("RETURN"), cancellable = true)
	private void aiTranslate$containerTooltip(ItemStack stack, CallbackInfoReturnable<List<Component>> callback) {
		List<Component> lines = callback.getReturnValue();
		List<Component> translated = TooltipLines.translated(lines);
		if (translated != lines) {
			callback.setReturnValue(translated);
		}
	}
}
