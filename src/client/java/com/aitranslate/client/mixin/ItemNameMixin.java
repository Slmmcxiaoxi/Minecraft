package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The item name that appears above the hotbar when the held item changes
 * (third feedback round: "手持物品切换时显示的物品名称").
 * <p>
 * {@code Gui#extractSelectedItemName} builds the label from
 * {@code ItemStack.getHoverName()} and then measures it to centre it over the
 * hotbar. The getter is redirected <em>inside that render method</em>, so the
 * width, the centring, the backdrop box and the rarity colour are all computed
 * from the translation; redirecting the final draw call would have left a
 * rectangle sized for the original text.
 * <p>
 * The getter is a data getter and is deliberately not modified anywhere else: the
 * item stack, its components and its NBT stay exactly as they are, and only the
 * value handed to the renderer changes. Vanilla item names are
 * {@code translatable} keys and are skipped by the extractor, so the vanilla
 * language file stays in charge of them; only hardcoded names - the ones a map
 * put there - are translated.
 * <p>
 * The pickup text ("拾取物品时的提示") is the very same overlay: the server does
 * not send a chat message for it in 26.1, {@code Gui#tick} arms this label.
 */
@Mixin(Gui.class)
public abstract class ItemNameMixin {

	@Redirect(method = "extractSelectedItemName", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;getHoverName()Lnet/minecraft/network/chat/Component;"))
	private Component aiTranslate$selectedItemName(ItemStack stack) {
		Component original = stack.getHoverName();
		Component translated = TranslationSupport.translated(original, TextType.ITEM);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"item:" + original.getString() + "->" + translated.getString(),
					"[hook] ITEM '{}' -> '{}'", original.getString(), translated.getString());
		}
		return translated;
	}
}
