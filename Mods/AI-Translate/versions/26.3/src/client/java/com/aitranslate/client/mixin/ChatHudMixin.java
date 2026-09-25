package com.aitranslate.client.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.capture.ComponentExtractor;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.config.ModConfig;
import com.aitranslate.client.display.TranslationSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Chat translation.
 * <p>
 * Minecraft 26.1 renamed {@code ChatHud} to {@code ChatComponent} and splits
 * every chat message into render lines ({@link GuiMessage.Line}) that are cached
 * in {@code trimmedMessages} <em>before</em> the render state is extracted.
 * <p>
 * This mixin post-processes that derived line cache: the lines of a message are
 * replaced by the lines of its translation, so the text appears at exactly the
 * same place, with the same fade and width handling. The message data
 * ({@link GuiMessage}) is never modified; the cache is rebuilt from the
 * untouched message list whenever the game re-trims, so switching the master
 * switch off restores the original chat instantly.
 */
@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {
	@Shadow
	@Final
	private List<GuiMessage.Line> trimmedMessages;

	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private int getWidth() {
		throw new AssertionError();
	}

	@Shadow
	private double getScale() {
		throw new AssertionError();
	}

	/** True while {@code refreshTrimmedMessages} rebuilds the whole list. */
	@Unique
	private boolean aiTranslate$refreshing;

	@Inject(method = "refreshTrimmedMessages", at = @At("HEAD"))
	private void aiTranslate$beforeRefresh(CallbackInfo info) {
		aiTranslate$refreshing = true;
	}

	@Inject(method = "refreshTrimmedMessages", at = @At("TAIL"))
	private void aiTranslate$afterRefresh(CallbackInfo info) {
		aiTranslate$refreshing = false;
		aiTranslate$process(0, trimmedMessages.size());
	}

	/** Newly arriving messages are appended to the head of the line cache. */
	@Inject(method = "addMessageToDisplayQueue", at = @At("TAIL"))
	private void aiTranslate$afterAdd(GuiMessage message, CallbackInfo info) {
		if (aiTranslate$refreshing) {
			return;
		}
		int end = 0;
		while (end < trimmedMessages.size() && trimmedMessages.get(end).parent() == message) {
			end++;
		}
		if (end > 0) {
			aiTranslate$process(0, end);
		}
	}

	/**
	 * Rewrites the given slice of the line cache. Message groups are delimited by
	 * {@link GuiMessage.Line#endOfEntry()} (which marks the first entry of a group
	 * because lines are added with {@code addFirst}).
	 */
	@Unique
	private void aiTranslate$process(int from, int to) {
		ModConfig config = AITranslateModClient.config;
		// The generic chat switch is checked per line (by its command kind), not here.
		if (config == null || !config.translateEnabled || AITranslateModClient.scheduler == null) {
			return;
		}
		if (from >= to || trimmedMessages.isEmpty()) {
			return;
		}
		Font font = this.minecraft.font;
		if (font == null) {
			return;
		}
		int width = Math.max(20, (int) Math.floor(this.getWidth() / Math.max(0.0001D, this.getScale())));

		List<GuiMessage.Line> rebuilt = new ArrayList<>(trimmedMessages.size() + 8);
		for (int index = 0; index < trimmedMessages.size(); index++) {
			GuiMessage.Line line = trimmedMessages.get(index);
			if (!line.endOfEntry() || index < from || index >= to) {
				rebuilt.add(line);
				continue;
			}
			int groupEnd = index + 1;
			while (groupEnd < trimmedMessages.size() && !trimmedMessages.get(groupEnd).endOfEntry()) {
				groupEnd++;
			}
			List<GuiMessage.Line> group = trimmedMessages.subList(index, groupEnd);
			rebuilt.addAll(aiTranslate$translateGroup(group, font, width));
			index = groupEnd - 1;
		}

		trimmedMessages.clear();
		trimmedMessages.addAll(rebuilt);
	}

	@Unique
	private List<GuiMessage.Line> aiTranslate$translateGroup(List<GuiMessage.Line> group, Font font, int width) {
		GuiMessage parent = group.get(0).parent();
		Component original = parent.content();
		if (original == null) {
			return new ArrayList<>(group);
		}
		// Which command produced this line decides which switch applies (thirteenth
		// feedback round): /me, /say, /msg, /tellraw and command feedback each have
		// their own toggle, and plain player chat uses the chat switch.
		com.aitranslate.client.capture.ChatKind kind = com.aitranslate.client.capture.ChatKind.classify(original);
		String flat = original.getString();
		java.util.List<String> extracted = ComponentExtractor.uniqueTexts(original);
		if (kind == com.aitranslate.client.capture.ChatKind.SCRIPT) {
			com.aitranslate.client.util.DebugLog.onceLazy("trace:tellraw:hook:" + flat, () ->
					"[trace:tellraw:1-hook] entered ComponentExtractor source='" + flat + "' tree="
							+ com.aitranslate.client.util.ComponentDiagnostics.describe(original) + " extracted="
							+ extracted);
		}
		if (extracted.isEmpty()) {
			return new ArrayList<>(group);
		}
		ModConfig config = AITranslateModClient.config;
		if (config != null && config.debugLog
				&& flat.matches("(?s).*\\[[^]]+]\\s*[A-Za-z0-9_]{1,16}\\s*<[^>]+>.*")) {
			com.aitranslate.client.util.DebugLog.onceLazy("multiplayer-team:" + flat, () -> {
				java.util.List<String> leaves = ComponentExtractor.uniqueTexts(original);
				java.util.List<String> decisions = leaves.stream().map(text -> "'" + text + "'="
						+ String.valueOf(AITranslateModClient.scheduler.detector().skipReason(text))).toList();
				return "[diagnostic:multiplayer-team] kind=" + kind + " text='" + flat + "' tree="
						+ com.aitranslate.client.util.ComponentDiagnostics.describe(original)
						+ " extracted=" + leaves + " decisions=" + decisions;
			});
		}
		// Command feedback is structurally filtered by ComponentExtractor: vanilla
		// translatable text, numbers/items and opaque /data payloads never become
		// candidates, while literal team decorations do.  Do not let the legacy
		// "command feedback" switch discard those safe leaves after extraction.
		// This keeps /data untouched but lets /give translate [Winner]/<Loser>.
		if (config != null && kind != com.aitranslate.client.capture.ChatKind.COMMAND_FEEDBACK
				&& !config.isChatKindEnabled(kind)) {
			return new ArrayList<>(group);
		}
		Component translated = TranslationSupport.translatedChat(original, kind);
		if (kind == com.aitranslate.client.capture.ChatKind.SCRIPT) {
			com.aitranslate.client.util.DebugLog.once("trace:tellraw:render:" + flat + "->" + translated.getString(),
					"[trace:tellraw:8-render] rendering={} source='{}' result='{}'",
					translated == original ? "original" : "translation", flat, translated.getString());
		}
		if (config != null && config.debugLog
				&& flat.matches("(?s).*\\[[^]]+]\\s*[A-Za-z0-9_]{1,16}\\s*<[^>]+>.*")) {
			com.aitranslate.client.util.DebugLog.once("multiplayer-team-render:" + flat + "->" + translated.getString(),
					"[diagnostic:multiplayer-team] final rendering={} result='{}'",
					translated == original ? "original" : "translation", translated.getString());
		}
		if (config != null && config.debugLog) {
			com.aitranslate.client.util.DebugLog.once(
					"chat:" + kind + ":" + original.getString() + "->" + translated.getString(),
					"[hook] CHAT kind={} source='{}' leaves={} -> '{}'", kind, original.getString(),
					ComponentExtractor.uniqueTexts(original), translated.getString());
		}
		if (translated == original) {
			return new ArrayList<>(group);
		}
		List<GuiMessage.Line> lines = aiTranslate$lines(parent, font, width, translated);
		return lines.isEmpty() ? new ArrayList<>(group) : lines;
	}

	/** Splits a component and returns the lines in bottom-up (list) order. */
	@Unique
	private List<GuiMessage.Line> aiTranslate$lines(GuiMessage parent, Font font, int width, Component component) {
		List<FormattedCharSequence> split = font.split(component, width);
		List<GuiMessage.Line> lines = new ArrayList<>(split.size());
		for (int i = split.size() - 1; i >= 0; i--) {
			lines.add(new GuiMessage.Line(parent, split.get(i), false));
		}
		if (!lines.isEmpty()) {
			GuiMessage.Line last = lines.get(lines.size() - 1);
			lines.set(lines.size() - 1, new GuiMessage.Line(last.parent(), last.content(), true));
		}
		return lines;
	}

	/**
	 * The player's own chat lines are translated too (third feedback round): what
	 * the player wrote is translated <em>when it is displayed</em>, while the name
	 * stays untouched - every literal leaf is checked against the online player
	 * names by {@code TextDetector}, so {@code <Steve> Hello} becomes
	 * {@code <Steve> 你好}. The data that goes to the server is never modified.
	 */
}
