package com.aitranslate.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.aitranslate.client.display.TooltipLines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Item tooltip lines at the deepest source (fifth feedback round).
 * <p>
 * With <strong>ModernUI</strong> installed neither {@code Screen#getTooltipFromItem}
 * nor {@code GuiGraphicsExtractor#setTooltipForNextFrame(Font, List, …)} is used for
 * item tooltips: ModernUI records the hovered stack and renders the tooltip with its
 * own layout engine, building the lines straight from {@code ItemStack#getTooltipLines}.
 * That is why the inventory tooltip stayed untranslated for a user who has ModernUI
 * installed, no matter how many of the render entry points were hooked.
 * <p>
 * Only the <em>returned list</em> is replaced - the item stack, its components and
 * its NBT are read and never written, so the rule "the data layer is read-only" still
 * holds. Components built by this mod are recognised and never translated twice, so
 * the other tooltip hooks stay harmless.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackTooltipMixin {

	@Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
	private void aiTranslate$tooltipLines(Item.TooltipContext context, Player player, TooltipFlag flag,
			CallbackInfoReturnable<List<Component>> callback) {
		// This getter is also used off the render thread (the creative-mode search
		// index builds item lists in worker threads). Those calls are not rendering,
		// must not queue translations and must not touch the render memo, so anything
		// that is not the client thread is left alone.
		if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) {
			return;
		}
		List<Component> lines = callback.getReturnValue();
		List<Component> translated = TooltipLines.translated(lines);
		if (translated != lines) {
			callback.setReturnValue(translated);
		}
	}
}
