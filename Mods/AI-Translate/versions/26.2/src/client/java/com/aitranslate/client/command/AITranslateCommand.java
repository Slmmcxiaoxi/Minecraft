package com.aitranslate.client.command;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.util.FileUtil;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client side {@code /aitranslate} command: master switch, status, cache
 * operations and shortcuts for the config screen.
 */
public final class AITranslateCommand {
	private AITranslateCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("aitranslate")
					.executes(context -> status(context.getSource()))
					.then(ClientCommands.literal("status").executes(context -> status(context.getSource())))
					.then(ClientCommands.literal("toggle").executes(context -> {
						AITranslateModClient.keybindings.toggleTranslation();
						return 1;
					}))
					.then(ClientCommands.literal("on").executes(context -> {
						AITranslateModClient.config.translateEnabled = true;
						AITranslateModClient.config.save();
						// Both directions rebuild the baked caches (chat lines and
						// friends), so "on" restores the translations that the memory
						// cache still holds and "off" restores the originals.
						AITranslateModClient.refreshNow();
						feedback(context.getSource(), "[AT] 翻译已开启");
						return 1;
					}))
					.then(ClientCommands.literal("off").executes(context -> {
						AITranslateModClient.config.translateEnabled = false;
						AITranslateModClient.config.save();
						AITranslateModClient.refreshNow();
						feedback(context.getSource(), "[AT] 翻译已关闭");
						return 1;
					}))
					.then(ClientCommands.literal("reload").executes(context -> {
						AITranslateModClient.config = com.aitranslate.client.config.ModConfig.load();
						AITranslateModClient.onConfigChanged();
						feedback(context.getSource(), "配置已重载");
						return 1;
					}))
					.then(ClientCommands.literal("config").executes(context -> {
						Minecraft client = Minecraft.getInstance();
						AITranslateModClient.openConfigScreen(client.gui.screen());
						return 1;
					}))
					.then(ClientCommands.literal("perf").executes(context -> {
						feedback(context.getSource(), AITranslateModClient.scheduler.performanceSummary());
						feedback(context.getSource(), "缓存: " + AITranslateModClient.cacheManager.currentSize()
								+ " 条 / 命中率 " + AITranslateModClient.cacheManager.hitRatePercent() + "% / 内存上限 "
								+ AITranslateModClient.config.memoryCacheSize + " 条 / 每秒请求上限 "
								+ AITranslateModClient.config.maxRequestsPerSecond);
						feedback(context.getSource(),
								com.aitranslate.client.display.RefreshCoordinator.summary());
						return 1;
					}))
					.then(ClientCommands.literal("refresh").executes(context -> {
						// Same as the refresh key, but clear the memory cache only when the
						// option is on, so the command can also be used to re-request texts.
						int dropped = AITranslateModClient.config.refreshClearsMemoryCache
								? com.aitranslate.client.display.RefreshCoordinator.clearMemoryCaches()
								: 0;
						AITranslateModClient.refreshNow();
						feedback(context.getSource(), dropped > 0
								? "[AT] 已刷新（清空内存缓存 " + dropped + " 条）"
								: "[AT] 已刷新");
						return 1;
					}))
					.then(ClientCommands.literal("cache")
							.then(ClientCommands.literal("clear").executes(context -> {
								AITranslateModClient.cacheManager.clearAll();
								feedback(context.getSource(), "已清空全部缓存");
								return 1;
							}))
							.then(ClientCommands.literal("current").executes(context -> {
								AITranslateModClient.cacheManager.clearCurrent();
								feedback(context.getSource(), "已清空当前存档缓存");
								return 1;
							}))
							.then(ClientCommands.literal("reload").executes(context -> {
								AITranslateModClient.cacheManager.current().load();
								AITranslateModClient.refreshRenderCaches();
								feedback(context.getSource(), "已重新加载缓存 ("
										+ AITranslateModClient.cacheManager.currentSize() + " 条)");
								return 1;
							}))
							.then(ClientCommands.literal("save").executes(context -> {
								AITranslateModClient.cacheManager.saveCurrent();
								feedback(context.getSource(), "缓存已写入磁盘");
								return 1;
							}))
							.then(ClientCommands.literal("open").executes(context -> {
								var cacheDir = AITranslateModClient.cacheManager.getRootDir();
								feedback(context.getSource(), "缓存目录: " + cacheDir.toAbsolutePath());
								return 1;
							}))
							.then(ClientCommands.literal("export").executes(context -> {
								if (AITranslateModClient.currentContext() == null) {
									feedback(context.getSource(), "当前没有缓存上下文");
									return 0;
								}
								try {
									var manager = AITranslateModClient.cacheManager;
									String directory = AITranslateModClient.currentContext().directoryName();
									String name = directory.replace('/', '_');
									var target = manager.exportDirectory().resolve(name + ".json");
									manager.writeBundle(manager.exportContext(directory), target);
									feedback(context.getSource(), "已导出到 " + target);
								} catch (Exception e) {
									feedback(context.getSource(), "导出失败: " + e.getMessage());
								}
								return 1;
							}))
							.then(ClientCommands.literal("cleanup").executes(context -> {
								java.util.Set<String> known = new java.util.HashSet<>();
								if (AITranslateModClient.currentContext() != null) {
									known.add(AITranslateModClient.currentContext().directoryName());
								}
								int removed = AITranslateModClient.cacheManager.cleanOrphanContexts(known);
								feedback(context.getSource(), "已清理 " + removed + " 个孤儿缓存");
								return removed;
							})));
			dispatcher.register(root);
		});
	}

	private static int status(FabricClientCommandSource source) {
		boolean enabled = AITranslateModClient.config.translateEnabled;
		feedback(source, "状态: " + (enabled ? "翻译中" : "已关闭")
				+ " | 上下文: " + (AITranslateModClient.currentContext() == null
						? "未进入世界"
						: AITranslateModClient.currentContext().identifier())
				+ " | 缓存: " + AITranslateModClient.cacheManager.currentSize() + " 条"
				+ " | 待翻译: " + AITranslateModClient.scheduler.pendingCount()
				+ " | 请求: " + AITranslateModClient.scheduler.requestCount()
				+ " / 失败: " + AITranslateModClient.scheduler.failureCount());
		feedback(source, "缓存目录: " + AITranslateModClient.cacheManager.getRootDir().toAbsolutePath()
				+ " (" + FileUtil.humanSize(
						FileUtil.directorySize(AITranslateModClient.cacheManager.getRootDir())) + ")");
		if (!AITranslateModClient.scheduler.lastError().isEmpty()) {
			feedback(source, "最近错误: " + AITranslateModClient.scheduler.lastError());
		}
		return 1;
	}

	private static void feedback(FabricClientCommandSource source, String text) {
		// Sixth feedback round: one short prefix for every mod output.
		Component message = Component.literal("[AT] " + text);
		source.sendFeedback(message);
	}
}
