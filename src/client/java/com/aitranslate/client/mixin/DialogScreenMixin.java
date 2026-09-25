package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.SingleKeyCache;

/**
 * Dialog body translation.
 * <p>
 * Dialog titles and buttons are drawn as single components and are covered by
 * {@link GuiGraphicsExtractorMixin}. Dialog bodies (and every other multi line
 * text widget) go through {@link MultiLineTextWidget}, which builds and caches a
 * {@link MultiLineLabel} from the widget message.
 * <p>
 * 26.1 turned {@code MultiLineLabel} into an interface whose {@code create}
 * factories are static, so the label itself cannot be mixed into; the widget is
 * the class to hook. Both factory calls in the widget's label loader are
 * redirected, and the substitution only happens while a
 * {@link DialogScreen} is open - everywhere else the widget keeps its text.
 * <p>
 * The widget message itself is never changed, so game data and the label cache
 * key stay intact.
 */
@Mixin(MultiLineTextWidget.class)
public abstract class DialogScreenMixin {
	@Shadow
	@Final
	private SingleKeyCache<?, ?> cache;

	@Unique
	private long aiTranslate$generation = Long.MIN_VALUE;

	/** {@code MultiLineLabel.create(font, component, width)}. */
	@Redirect(method = "lambda$new$0", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/MultiLineLabel;create(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;I)Lnet/minecraft/client/gui/components/MultiLineLabel;"))
	private static MultiLineLabel aiTranslate$dialogLine(Font font, Component component, int width) {
		return MultiLineLabel.create(font, aiTranslate$body(component), width);
	}

	/**
	 * Drops the cached label when the render generation changed, so a dialog that is
	 * already open picks up translations as soon as they arrive (its label would
	 * otherwise stay as it was first built).
	 */
	@Inject(method = "visitLines", at = @At("HEAD"))
	private void aiTranslate$refreshLabel(ActiveTextCollector collector, CallbackInfo info) {
		long generation = TranslationSupport.generation();
		if (aiTranslate$generation == generation) {
			return;
		}
		aiTranslate$generation = generation;
		((SingleKeyCacheAccessor) (Object) this.cache).aiTranslate$setCacheKey(null);
	}

	/** {@code MultiLineLabel.create(font, maxWidth, lineSpacing, components...)}. */
	@Redirect(method = "lambda$new$0", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/MultiLineLabel;create(Lnet/minecraft/client/gui/Font;II[Lnet/minecraft/network/chat/Component;)Lnet/minecraft/client/gui/components/MultiLineLabel;"))
	private static MultiLineLabel aiTranslate$dialogLines(Font font, int maxWidth, int lineSpacing,
			Component[] components) {
		if (!aiTranslate$isDialog()) {
			return MultiLineLabel.create(font, maxWidth, lineSpacing, components);
		}
		Component[] translated = new Component[components.length];
		for (int i = 0; i < components.length; i++) {
			translated[i] = aiTranslate$body(components[i]);
		}
		return MultiLineLabel.create(font, maxWidth, lineSpacing, translated);
	}

	private static Component aiTranslate$body(Component component) {
		if (!aiTranslate$isDialog()) {
			return component;
		}
		return TranslationSupport.translated(component, TextType.DIALOG);
	}

	private static boolean aiTranslate$isDialog() {
		return Minecraft.getInstance().screen instanceof DialogScreen<?>;
	}
}
