package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.display.TranslationSupport;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Entity custom name translation.
 * <p>
 * 26.1 submits entity name tags to the render graph
 * ({@code SubmitNodeCollector#submitNameTag}). Redirecting that submission lets
 * the mod translate the rendered name while keeping the exact same transform,
 * attachment point, lighting and distance - only the component changes.
 * <p>
 * Only entities that actually carry a hardcoded custom name produce a literal
 * (vanilla entity names are {@code translatable} and therefore skipped), and the
 * scoreboard score above a player's head is numeric, so it is skipped as well.
 * <p>
 * Tenth feedback round: the submission also carries {@code distanceToCamera}, so
 * the name of an entity standing right next to the player is requested at HIGH
 * priority while a name at the horizon waits (see
 * {@code FocusTracker#priorityForDistance}).
 */
@Mixin(EntityRenderer.class)
public abstract class EntityNameMixin {
	/** The render state makes player identity unambiguous; text heuristics do not. */
	@Unique
	private boolean aiTranslate$renderingPlayerName;

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
			at = @At("HEAD"))
	private void aiTranslate$rememberEntityType(EntityRenderState state, PoseStack pose,
			SubmitNodeCollector collector, CameraRenderState camera, int y, CallbackInfo info) {
		aiTranslate$renderingPlayerName = state != null && state.entityType == EntityType.PLAYER;
	}

	@Redirect(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZIDLnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
	private void aiTranslate$nameTag(SubmitNodeCollector collector, PoseStack pose, Vec3 attachment, int y,
			Component component, boolean shadow, int light, double distanceToCamera, CameraRenderState camera) {
		var priority = com.aitranslate.client.focus.FocusTracker
				.priorityForDistance(TextType.ENTITY_NAME, distanceToCamera);
		if (!com.aitranslate.client.focus.FocusTracker.withinTranslationRange(
				TextType.ENTITY_NAME, component.getString(), distanceToCamera)) {
			collector.submitNameTag(pose, attachment, y, component, shadow, light, distanceToCamera, camera);
			return;
		}
		// Protect only the real profile name. PlayerTeam formats the visible component
		// as prefix + profile name + suffix; skipping the whole component also skipped
		// the two map-authored decorations. The structural identity lookup closes the
		// first-frame race before PlayerList has refreshed.
		if (aiTranslate$renderingPlayerName
				&& com.aitranslate.client.AITranslateModClient.scheduler != null) {
			String identity = com.aitranslate.client.capture.ComponentExtractor.playerIdentity(component);
			com.aitranslate.client.AITranslateModClient.scheduler.detector().protectPlayerName(identity);
		}
		Component translated = TranslationSupport.translated(component, TextType.ENTITY_NAME, priority);
		if (com.aitranslate.client.AITranslateModClient.config != null
				&& com.aitranslate.client.AITranslateModClient.config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"entity:" + component.getString() + "->" + translated.getString(),
					"[hook] ENTITY_NAME '{}' -> '{}' (distance {} = {})", component.getString(),
					translated.getString(), String.format("%.1f", distanceToCamera), priority);
		}
		collector.submitNameTag(pose, attachment, y, translated, shadow, light, distanceToCamera, camera);
	}
}
