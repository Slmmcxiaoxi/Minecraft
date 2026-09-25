package com.aitranslate.client.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.SignTranslationLayout;
import com.aitranslate.client.display.TranslationSupport;
import com.aitranslate.client.focus.FocusTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.Vec3;

/**
 * Sign translation (front and back side, four lines each).
 * <p>
 * {@code AbstractSignRenderer#submitSignText} converts the four sign messages
 * into render lines through a mapper function. Replacing the component inside
 * that mapper means the translation inherits <em>everything</em> from the
 * original call: the sign's transformation matrix, orientation, light value,
 * centering offset, text colour, glow outline and render pass.
 * <p>
 * This is why the translation is substituted instead of drawn as an extra
 * floating block - an additional submission needs its own transform and was the
 * cause of the misaligned translations reported after the MVP test.
 * <p>
 * Tenth feedback round: the mapper only receives the component, so the position of
 * the sign is picked up at the head of {@code submitSignText} (which runs
 * synchronously on the render thread right before the mapper) and used to give a
 * sign that stands next to the player a higher request priority than one at the
 * edge of the view.
 * <p>
 * The {@code SignText} data of the block entity is read but never modified, so
 * map logic that checks sign contents keeps working.
 */
@Mixin(AbstractSignRenderer.class)
public abstract class SignTextMixin {

	/** Position of the sign currently being submitted (render thread only). */
	@Unique
	private static BlockPos aiTranslate$currentSignPos;
	@Unique
	private static List<String> aiTranslate$renderLines = List.of("", "", "", "");
	@Unique
	private static List<String> aiTranslate$originalLines = List.of("", "", "", "");
	@Unique
	private static int aiTranslate$lineIndex;
	@Unique
	private static String aiTranslate$wholeSignKey = "";
	@Unique
	private static com.aitranslate.client.scheduler.TranslationPriority aiTranslate$priority;
	@Unique
	private static boolean aiTranslate$editingSign;
	@Unique
	private static boolean aiTranslate$withinRange;

	@Inject(method = "submitSignText", at = @At("HEAD"))
	private void aiTranslate$rememberSignPosition(SignRenderState state, PoseStack pose,
			SubmitNodeCollector collector, SignText text, CallbackInfo info) {
		aiTranslate$currentSignPos = state == null ? null : state.blockPos;
		aiTranslate$lineIndex = 0;
		if (text == null) {
			aiTranslate$renderLines = List.of("", "", "", "");
			aiTranslate$originalLines = aiTranslate$renderLines;
			aiTranslate$wholeSignKey = "";
			return;
		}
		Component[] messages = text.getMessages(state != null && state.isTextFilteringEnabled);
		List<String> originals = new ArrayList<>(SignTranslationLayout.MAX_LINES);
		for (int i = 0; i < SignTranslationLayout.MAX_LINES; i++) {
			Component line = i < messages.length ? messages[i] : null;
			originals.add(line == null ? "" : line.getString());
		}
		aiTranslate$originalLines = List.copyOf(originals);
		aiTranslate$wholeSignKey = SignTranslationLayout.requestText(originals);
		aiTranslate$renderLines = aiTranslate$originalLines;
		aiTranslate$editingSign = aiTranslate$isEditingSign();
		double distance = aiTranslate$distanceToPlayer();
		aiTranslate$priority = FocusTracker.priorityForDistance(TextType.SIGN, distance);
		aiTranslate$withinRange = FocusTracker.withinTranslationRange(TextType.SIGN,
				aiTranslate$wholeSignKey, distance);
	}

	@ModifyVariable(method = "lambda$submitSignText$0", at = @At("HEAD"), argsOnly = true)
	private Component aiTranslate$signLine(Component component) {
		int index = Math.min(aiTranslate$lineIndex++, SignTranslationLayout.MAX_LINES - 1);
		// The mapper only runs when vanilla rebuilds SignText.renderMessages. Doing the
		// one whole-sign lookup here (on row zero), rather than at submitSignText HEAD,
		// prevents every visible sign from checking/requesting on every rendered frame.
		if (index == 0 && !aiTranslate$editingSign && aiTranslate$withinRange) {
			String translatedBlock = TranslationSupport.translatedBlockText(aiTranslate$wholeSignKey,
					TextType.SIGN, aiTranslate$priority, null);
			aiTranslate$renderLines = SignTranslationLayout.layout(aiTranslate$originalLines, translatedBlock);
		}
		String rendered = index < aiTranslate$renderLines.size() ? aiTranslate$renderLines.get(index)
				: component.getString();
		Component translated = rendered.equals(component.getString()) ? component
				: Component.literal(rendered).setStyle(component.getStyle());
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"sign-block:" + aiTranslate$wholeSignKey + "->" + String.join("\n", aiTranslate$renderLines),
					"[hook] SIGN block='{}' -> '{}' (distance {}, row {})",
					aiTranslate$wholeSignKey.replace("\n", " / "),
					String.join(" / ", aiTranslate$renderLines),
					String.format("%.1f", aiTranslate$distanceToPlayer()), index + 1);
		}
		return translated;
	}

	/** No requests and no substitutions while the player edits either sign side. */
	@Unique
	private static boolean aiTranslate$isEditingSign() {
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		return screen != null && screen.getClass().getName().endsWith("SignEditScreen");
	}

	/** Distance from the player's eyes to the sign being submitted, or {@code NaN}. */
	@Unique
	private static double aiTranslate$distanceToPlayer() {
		BlockPos pos = aiTranslate$currentSignPos;
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (pos == null || player == null) {
			return Double.NaN;
		}
		return Math.sqrt(player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
	}
}
