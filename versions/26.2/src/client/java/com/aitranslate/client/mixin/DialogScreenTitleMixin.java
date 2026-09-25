package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.network.chat.Component;

/**
 * Dialog title, translated <em>before</em> the title widget is built (eighth
 * feedback round: the title was translated but stayed off-centre).
 * <p>
 * {@code DialogScreen#createTitleWithWarningButton} constructs a {@code StringWidget},
 * and that constructor measures the text to set the widget's width (the header
 * {@code FrameLayout} then centres the widget box). Translating only at draw time
 * meant the box kept the width of the original text: a longer translation overflowed
 * symmetrically, a shorter one sat inside an oversized box, and after a later
 * re-measure the box no longer matched the position the layout had given it.
 * <p>
 * Handing the translated text to the constructor makes the widget's own width (and
 * therefore the centring) correct from the start. A translation that arrives while
 * the dialog is open is handled by {@code RefreshCoordinator}, which rebuilds the
 * dialog screen once the new text is cached - the widget is then constructed again
 * with the translation.
 * <p>
 * <strong>Twelfth feedback round:</strong> that rebuild is also why the title
 * overlapped on the first open of a dialog. {@code DialogScreen} keeps its
 * {@link HeaderAndFooterLayout} in a field created by the constructor and appends
 * to it on every {@code init()} - while {@code Screen#rebuildWidgets()} only clears
 * the screen's widget lists. A rebuild therefore left the header holding
 * <em>two</em> title widgets (the one built with the original text and the one
 * built with the translation) and two body layouts, and both were drawn - the
 * original and the translation on top of each other. The second open looked fine
 * because the translation was cached and no rebuild was needed.
 * <p>
 * The fix is the injection below: a repeated {@code init()} starts from a fresh
 * layout, so a rebuild replaces the header instead of growing it. The originally
 * intended behaviour (a title box measured from the translation) is then reached
 * without any leftover widget.
 */
@Mixin(DialogScreen.class)
public abstract class DialogScreenTitleMixin {

	/** The layout the screen appends its header, body and footer to. */
	@Shadow
	@Final
	@Mutable
	private HeaderAndFooterLayout layout;

	/**
	 * Created by the first {@code init()}; {@code null} until then, which is how a
	 * rebuild is told apart from the first build.
	 */
	@Shadow
	private ScrollableLayout bodyScroll;

	/**
	 * Starts a repeated {@code init()} (the rebuild after a translation arrived)
	 * from an empty layout.
	 * <p>
	 * Without this the header and the contents accumulate one more copy of the title
	 * widget, the warning button and the body on every rebuild - the title was drawn
	 * twice, once in the original language and once translated.
	 */
	@Inject(method = "init", at = @At("HEAD"))
	private void aiTranslate$resetLayoutBeforeRebuild(CallbackInfo info) {
		if (this.bodyScroll == null) {
			// First build: the constructor's layout is still empty.
			return;
		}
		this.layout = new HeaderAndFooterLayout((Screen) (Object) this);
	}


	@Redirect(method = "createTitleWithWarningButton", at = @At(value = "NEW",
			target = "(Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/Font;)"
					+ "Lnet/minecraft/client/gui/components/StringWidget;"))
	private StringWidget aiTranslate$titleWidget(Component title, Font font) {
		Component translated = TranslationSupport.translated(title, TextType.DIALOG);
		StringWidget widget = new StringWidget(translated, font);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			// The widget measured the text it was constructed with: logging the width
			// proves that the box the header layout centres is the translation's box,
			// not the original's (eighth feedback round: the title was off-centre).
			com.aitranslate.client.util.DebugLog.once(
					"dialogtitle:" + title.getString() + "->" + translated.getString(),
					"[hook] DIALOG_TITLE '{}' -> '{}' (widget width {} px = translated text width {})",
					title.getString(), translated.getString(), widget.getWidth(),
					font.width(translated));
		}
		return widget;
	}
}
