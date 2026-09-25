package com.aitranslate.client.config;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** One visual language for cache and dictionary import/export operations. */
public final class OperationFeedback {
	private static final Logger LOGGER = AITranslateMod.LOGGER;
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(4000L);

	private OperationFeedback() {
	}

	public static void progress(String action) {
		show(action.startsWith("正在") ? action : "正在" + action + "...");
	}

	public static void success(String action) {
		show(action + "成功");
	}

	public static void success(String action, String detail) {
		show(action + "成功", detail);
	}

	public static void failure(String action, String reason) {
		show(action + "失败", clean(reason));
	}

	public static void show(String message) {
		show(message, "");
	}

	private static void show(String message, String detail) {
		try {
			var manager = Minecraft.getInstance().gui.toastManager();
			if (manager != null) {
				SystemToast.add(manager, TOAST_ID, Component.literal("[AT] " + message),
						Component.literal(detail == null ? "" : detail));
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not show operation feedback", e);
		}
	}

	private static String clean(String reason) {
		return reason == null || reason.isBlank() ? "未知错误" : reason;
	}
}
