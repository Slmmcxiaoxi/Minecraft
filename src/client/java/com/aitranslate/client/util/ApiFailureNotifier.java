package com.aitranslate.client.util;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * "The API is not reachable" handling (fourth feedback round, revised in the
 * tenth).
 * <p>
 * The fourth round switched translation off after the <em>first</em> request that
 * failed after its retries. That turned out to be too eager: one long book page
 * could time out on its own, and because a switched-off mod translates nothing,
 * long text could never be translated at all - the vicious circle the tenth
 * feedback round reported.
 * <p>
 * The policy now lives in {@code FailurePolicy}: only consecutive
 * <em>transport</em> failures count, they are counted by the scheduler, and this
 * class is only called once the threshold is reached (five by default, two for a
 * clearly wrong key/model/URL). Below the threshold nothing is shown - the text
 * keeps its original and the queue keeps going.
 * <p>
 * The message always says what happened and how to recover, both as a chat line
 * (bottom left, where the player is looking for messages) and as a toast (which
 * stays visible when the chat has scrolled away). Nothing here changes the
 * original text: switching the master switch off simply means the render layer
 * hands the original components through again.
 */
public final class ApiFailureNotifier {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	/** Don't repeat the notification more often than this. */
	private static final long COOLDOWN_MS = 30_000L;

	private static final AtomicLong LAST_NOTIFIED = new AtomicLong(0L);
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(6000L);

	private ApiFailureNotifier() {
	}

	/**
	 * Called from the scheduler once the API failed often enough in a row.
	 * Thread safe; the visible part is marshalled onto the client thread.
	 *
	 * @param reason the last error message
	 * @param consecutiveFailures how many requests failed in a row
	 */
	public static void onPermanentFailure(String reason, int consecutiveFailures) {
		ModConfig config = AITranslateModClient.config;
		if (config == null || !config.translateEnabled) {
			return;
		}
		long now = System.currentTimeMillis();
		long last = LAST_NOTIFIED.get();
		if (now - last < COOLDOWN_MS || !LAST_NOTIFIED.compareAndSet(last, now)) {
			return;
		}
		boolean switchedOff = false;
		if (config.autoDisableOnApiFailure) {
			config.translateEnabled = false;
			config.save();
			switchedOff = true;
		}
		LOGGER.warn("Translation API failed {} time(s) in a row ({}); master switch {}", consecutiveFailures,
				reason, switchedOff ? "switched off" : "left on (auto-disable disabled)");
		final boolean off = switchedOff;
		final String detail = reason == null || reason.isBlank() ? "未知错误" : reason;
		final int count = Math.max(1, consecutiveFailures);
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return;
		}
		client.execute(() -> {
			if (off) {
				// Auto-disable flips the master switch off-screen, so the baked chat
				// and advancement caches have to be rebuilt here as well - otherwise
				// the player keeps reading translated text while the mod claims to be
				// off (ninth feedback round: same rule as the manual switch).
				com.aitranslate.client.display.RefreshCoordinator.refreshNow(client);
			}
			ChatFeedback.send("⚠ 翻译 API 连续 " + count + " 次请求失败，已暂停翻译：" + detail);
			if (off) {
				ChatFeedback.send("已自动关闭翻译总开关，游戏恢复显示原文（原文与存档数据从未被修改）。");
			} else {
				ChatFeedback.send("翻译总开关保持开启，其余文本会继续翻译（配置界面 → 高级 里可改为自动关闭）。");
			}
			ChatFeedback.send("恢复方式：修好 API（Base URL / Key / 模型名，或用「连接测试」按钮验证）后，按 "
					+ new com.aitranslate.client.keybinding.SingleKeyBinding(
							AITranslateModClient.config.toggleTranslateKey).describe()
					+ " 或执行 /aitranslate on 重新开启。");
			var toasts = client.getToastManager();
			if (toasts != null) {
				SystemToast.add(toasts, TOAST_ID,
						Component.literal("AI 翻译：API 连接失败 " + count + " 次"),
						Component.literal(off ? "已暂停翻译，显示原文；修好后按快捷键重新开启"
								: "翻译已暂停请求，其余文本继续"));
			}
			com.aitranslate.client.display.RefreshCoordinator.request();
		});
	}
}
