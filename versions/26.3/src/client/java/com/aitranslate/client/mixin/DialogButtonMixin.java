package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.dialog.DialogControlSet;
import net.minecraft.network.chat.Component;

/**
 * Translates dialog action/choice labels before their widgets are constructed.
 * <p>
 * Hooking the builder input, rather than the final draw call, means the Button
 * measures and centres the translated component itself. The ActionButton and its
 * click event remain untouched; only the display component passed to the widget is
 * replaced.
 */
@Mixin(DialogControlSet.class)
public abstract class DialogButtonMixin {

	@Redirect(method = "createDialogButton", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/Button;builder(Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/components/Button$OnPress;)Lnet/minecraft/client/gui/components/Button$Builder;"))
	private static Button.Builder aiTranslate$dialogButton(Component label, Button.OnPress onPress) {
		return Button.builder(TranslationSupport.translated(label, TextType.DIALOG), onPress);
	}
}
