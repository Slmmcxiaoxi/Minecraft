package com.aitranslate.client.config;

import java.util.ArrayList;
import java.util.List;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.cache.CacheManager;
import com.aitranslate.client.capture.ChatKind;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.config.entry.ActionEntry;
import com.aitranslate.client.config.entry.FullWidthStringEntry;
import com.aitranslate.client.config.entry.SingleKeyEntry;
import com.aitranslate.client.config.entry.MultiActionEntry;
import com.aitranslate.client.config.entry.StatusDotEntry;
import com.aitranslate.client.config.entry.SwitchRowEntry;
import com.aitranslate.client.util.FileUtil;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cloth Config based configuration screen, restructured in the thirteenth feedback
 * round.
 * <p>
 * The six pages follow the navigation order of the feedback document - 全局,
 * API 设置, 翻译控制, 词典, 缓存管理, 高级设置 - and the left/right arrows of the tab bar
 * are always shown, so a page can be reached without aiming at its tab. Inside a
 * page everything that is not needed every day lives in a <em>collapsed</em>
 * sub-category, which is what keeps the pages short: a player sees the few switches
 * that matter and opens the rest when they need it.
 * <p>
 * Layout rules kept from the earlier rounds: entries only - nothing pinned to the
 * bottom of the screen (so nothing can cover the screen's own Save/Cancel/Back
 * buttons), long values use a full width two row field, and key bindings are
 * rebound by clicking their box.
 */
public final class ConfigScreen {
	private static final org.slf4j.Logger LOGGER = com.aitranslate.AITranslateMod.LOGGER;

	/** Last result of the connection test - shown as the dot on the API page. */
	private static volatile StatusDotEntry.State apiStatus = StatusDotEntry.State.UNKNOWN;
	private static final java.util.concurrent.atomic.AtomicLong API_TEST_SEQUENCE =
			new java.util.concurrent.atomic.AtomicLong();

	/**
	 * The screen that opened the config, kept so a rebuild after a cache action can
	 * return to it (fifteenth feedback round: the cache list has to refresh in place).
	 */
	private static Screen lastParent;

	private ConfigScreen() {
	}

	public static Screen create(Screen parent) {
		AITranslateModClient.synchronizeContext(Minecraft.getInstance());
		ModConfig config = AITranslateModClient.config;
		lastParent = parent;
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("AI Translate 配置"))
				// The tab bar stays visible and scrolls with its own arrows, so the pages
				// are reachable by clicking left/right instead of only by hitting a tab.
				.setAlwaysShowTabs(true)
				.setShouldTabsSmoothScroll(true)
				// Fifteenth feedback round: the entry list scrolls smoothly as well, which
				// is what makes a long cache list usable (a page of many worlds).
				.setShouldListSmoothScroll(true)
				.setSavingRunnable(() -> {
					config.save();
					AITranslateModClient.onConfigChanged();
				});
		ConfigEntryBuilder eb = builder.entryBuilder();

		buildPages(builder, eb, config);

		return builder.build();
	}

	/** Fills the six configuration pages. */
	private static void buildPages(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig config) {
		buildGlobal(builder, eb, config);
		buildApi(builder, eb, config);
		buildTranslationControl(builder, eb, config);
		buildDictionary(builder, eb, config);
		buildCache(builder, eb, config);
		buildAdvanced(builder, eb, config);
	}

	private static void confirmReset() {
		Minecraft client = Minecraft.getInstance();
		client.gui.setScreen(new ConfirmResetScreen(client.gui.screen()));
	}

	static void resetToDefaults() {
		// Write a fresh default config to disk and load it back into memory.
		new ModConfig().save();
		AITranslateModClient.config = ModConfig.load();
		AITranslateModClient.onConfigChanged();
	}

	// ------------------------------------------------------------- 全局

	private static void buildGlobal(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("全局设置"));

		category.addEntry(eb.startBooleanToggle(Component.literal("翻译开关"), config.translateEnabled)
				.setSaveConsumer(value -> {
					config.translateEnabled = value;
					AITranslateModClient.onConfigChanged();
				})
				.build());

		SubCategoryBuilder keys = collapsed(eb, "按键绑定");
		keys.add(new SingleKeyEntry(Component.literal("配置菜单"),
				() -> AITranslateModClient.keybindings == null ? com.aitranslate.client.keybinding.SingleKeyBinding.unbound()
						: AITranslateModClient.keybindings.openConfigBinding(),
				binding -> {
					if (AITranslateModClient.keybindings != null) {
						AITranslateModClient.keybindings.setOpenConfigBinding(binding);
					}
				}));
		keys.add(new SingleKeyEntry(Component.literal("翻译总开关"),
				() -> AITranslateModClient.keybindings == null ? com.aitranslate.client.keybinding.SingleKeyBinding.unbound()
						: AITranslateModClient.keybindings.toggleBinding(),
				binding -> {
					if (AITranslateModClient.keybindings != null) {
						AITranslateModClient.keybindings.setToggleBinding(binding);
					}
				}));
		keys.add(new SingleKeyEntry(Component.literal("刷新翻译"),
				() -> AITranslateModClient.keybindings == null ? com.aitranslate.client.keybinding.SingleKeyBinding.unbound()
						: AITranslateModClient.keybindings.refreshBinding(),
				binding -> {
					if (AITranslateModClient.keybindings != null) {
						AITranslateModClient.keybindings.setRefreshBinding(binding);
					}
				}));
		keys.add(new SwitchRowEntry(Component.literal("界面内切换"),
				new SingleKeyEntry(Component.empty(),
						() -> AITranslateModClient.keybindings == null ? com.aitranslate.client.keybinding.SingleKeyBinding.unbound()
								: AITranslateModClient.keybindings.originalBinding(),
						binding -> {
							if (AITranslateModClient.keybindings != null) {
								AITranslateModClient.keybindings.setOriginalBinding(binding);
							}
						},
						// The box fills the column the row gives it, so it ends on the same
						// edge as the binding boxes of the other rows (sixteenth round, 1.2).
						SingleKeyEntry.FILL_COLUMN),
				config.showScreenButton,
				value -> {
					config.showScreenButton = value;
					AITranslateModClient.onConfigChanged();
				},
				ConfigScreen::openPositionScreen));
		category.addEntry(keys.build());

		// Seventeenth feedback round, 1.1: the language and display fields used to sit two
		// levels deep (翻译设置 → 翻译语言 / 翻译显示). Cloth draws the content of an expanded
		// group inside the scrollable list, and in a small window that put the fields below
		// the list's visible area - where the click never arrives (measured: fields at
		// y=228/340 with the list ending at y=208 - see the README, section 二十七). The
		// wrapper group is gone: both groups are siblings of 按键绑定 now, one level of
		// nesting less, and the fields come into view without hunting through the scroll.
		SubCategoryBuilder languages = collapsed(eb, "翻译语言");
		languages.add(new FullWidthStringEntry(Component.literal("源语言"), config.sourceLang, () -> "auto",
				value -> config.sourceLang = value, false, Component.literal("auto = 自动判断")));
		languages.add(new FullWidthStringEntry(Component.literal("目标语言"), config.targetLang, () -> "zh_cn",
				value -> config.targetLang = value, false,
				Component.literal("首次启动按游戏语言填入，之后以这里的值为准")));
		category.addEntry(languages.build());
		SubCategoryBuilder display = collapsed(eb, "翻译显示");
		display.add(new FullWidthStringEntry(Component.literal("译文颜色"), config.translationColor, () -> "",
				value -> config.translationColor = value, false, Component.literal("#RRGGBB 或 &a")));
		display.add(new FullWidthStringEntry(Component.literal("译文前缀"), config.translationPrefix, () -> "",
				value -> config.translationPrefix = value, false, Component.literal("例如 “[译] ”")));
		category.addEntry(display.build());
	}

	// ---------------------------------------------------------- API 设置

	private static void buildApi(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("API 设置"));

		FullWidthStringEntry modelEntry = new FullWidthStringEntry(Component.literal("模型名称"), config.model,
				() -> "deepseek-chat", value -> config.model = value, false, Component.literal("必须与服务端一致"));
		FullWidthStringEntry urlEntry = new FullWidthStringEntry(Component.literal("API URL"), config.apiBaseUrl,
				() -> "https://api.deepseek.com", value -> config.apiBaseUrl = value, false,
				Component.literal("自动补全 /v1/chat/completions"));
		FullWidthStringEntry keyEntry = new FullWidthStringEntry(Component.literal("API Key"), config.apiKey,
				() -> "", value -> config.apiKey = value, true, Component.literal("只写入本地配置文件"));

		// Read getValue() at click time. Cloth does not run save consumers until the
		// screen is saved, which is why the old button tested stale settings and only
		// worked after closing and reopening the page.
		category.addEntry(new StatusDotEntry(Component.literal("连接状态"), () -> apiStatus,
				Component.literal("连接测试"), () -> testConnection(config, modelEntry.getValue(),
						urlEntry.getValue(), keyEntry.getValue())));
		category.addEntry(modelEntry);
		category.addEntry(urlEntry);

		SubCategoryBuilder key = collapsed(eb, "API Key");
		key.add(keyEntry);
		category.addEntry(key.build());
	}

	// ---------------------------------------------------------- 翻译控制

	/**
	 * The 翻译控制 page (seventeenth feedback round: the page was called 翻译选项 and is
	 * rebuilt to the structure the feedback document gives).
	 * <p>
	 * Structure, in order:
	 * <pre>
	 * 聊天框类++  聊天信息 / 命令类++ (/me / /msg /tell / /say / /tellraw)
	 * 方块类++    告示牌 / 容器标题
	 * 实体类++    实体名称 / 展示实体
	 * 物品类++    物品文本 / 成书 / 书与笔
	 * HUD类++     玩家列表 / 命令类++ (/title /bossbar /scoreboard /dialog /advancement)
	 * </pre>
	 * Removed by the document: 命令反馈 (command feedback keeps its "off" default),
	 * 其他类 as a group, and the controls for 翻译原版物品名 and 翻译符号包裹的文本 (both
	 * features keep their defaults and stay reachable through the config file / the
	 * command, they simply do not need a switch in the screen any more).
	 */
	private static void buildTranslationControl(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("翻译控制"));

		// 聊天框类: 聊天信息 plus a nested 命令类 group with the four chat commands (the
		// structure the seventeenth feedback document gives, as corrected by the user: the
		// command group belongs *under* 聊天框类, not next to it).
		SubCategoryBuilder chat = collapsed(eb, "聊天框类");
		chat.add(sourceToggle(eb, config, TextType.CHAT, "聊天信息", null));
		SubCategoryBuilder chatCommands = collapsed(eb, "命令类");
		chatCommands.add(chatKindToggle(eb, config, ChatKind.EMOTE));
		chatCommands.add(chatKindToggle(eb, config, ChatKind.PRIVATE));
		chatCommands.add(chatKindToggle(eb, config, ChatKind.ANNOUNCEMENT));
		chatCommands.add(chatKindToggle(eb, config, ChatKind.SCRIPT));
		chat.add(chatCommands.build());
		category.addEntry(chat.build());

		SubCategoryBuilder blocks = collapsed(eb, "方块类");
		blocks.add(sourceToggle(eb, config, TextType.SIGN, "告示牌", null));
		blocks.add(sourceToggle(eb, config, TextType.CONTAINER, "容器标题", null));
		category.addEntry(blocks.build());

		SubCategoryBuilder entities = collapsed(eb, "实体类");
		entities.add(sourceToggle(eb, config, TextType.ENTITY_NAME, "实体名称", null));
		entities.add(sourceToggle(eb, config, TextType.TEXT_DISPLAY, "展示实体（文本）", null));
		category.addEntry(entities.build());

		SubCategoryBuilder items = collapsed(eb, "物品类");
		// 物品名称 and 提示框 are one source for the player: the name of the item in hand
		// and the tooltip of the item under the cursor are the same text on screen, and the
		// document asks for them to be merged into 物品文本.
		items.add(eb.startBooleanToggle(Component.literal("物品文本"), config.enableItemTextTranslate)
				.setTooltip(Component.literal("名称与提示框"))
				.setSaveConsumer(value -> {
					config.enableItemTextTranslate = value;
					notifySourceSwitch();
				})
				.build());
		items.add(sourceToggle(eb, config, TextType.BOOK, "成书", null));
		items.add(eb.startBooleanToggle(Component.literal("书与笔"), config.translateBookEditPage)
				.setTooltip(Component.literal("关闭后仍可在书内手动切换"))
				.setSaveConsumer(value -> {
					config.translateBookEditPage = value;
					notifySourceSwitch();
				})
				.build());
		category.addEntry(items.build());

		SubCategoryBuilder hud = collapsed(eb, "HUD类");
		hud.add(sourceToggle(eb, config, TextType.PLAYER_LIST, "玩家列表", null));
		// The command group keeps the command as its name and explains itself in the
		// tooltip, which is what the document asks for.
		SubCategoryBuilder commands = collapsed(eb, "命令类");
		commands.add(sourceToggle(eb, config, TextType.TITLE, "/title",
				Component.literal("标题、副标题与动作栏")));
		commands.add(sourceToggle(eb, config, TextType.BOSS_BAR, "/bossbar", Component.literal("首领栏")));
		commands.add(sourceToggle(eb, config, TextType.SCOREBOARD, "/scoreboard", Component.literal("计分板")));
		commands.add(sourceToggle(eb, config, TextType.DIALOG, "/dialog", Component.literal("对话框")));
		commands.add(sourceToggle(eb, config, TextType.ADVANCEMENT, "/advancement", Component.literal("成就")));
		hud.add(commands.build());
		category.addEntry(hud.build());
	}

	/**
	 * A text source switch.
	 * <p>
	 * Seventeenth feedback round, 2.3: switching a source off has to change what is on
	 * screen <em>immediately</em>, even for texts that are already translated and cached -
	 * which is what {@link #notifySourceSwitch()} does (the same notification the master
	 * switch sends). No per-entry default value is set: Cloth draws a reset arrow for
	 * entries that have one, and the document asks for those to be gone (the global
	 * 恢复默认设置 is the only reset).
	 *
	 * @param tooltip extra explanation, or {@code null}
	 */
	private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> sourceToggle(ConfigEntryBuilder eb,
			ModConfig config, TextType type, String label, Component tooltip) {
		var entry = eb.startBooleanToggle(Component.literal(label), config.isSourceEnabled(type))
				.setSaveConsumer(value -> {
					config.setSourceEnabled(type, value);
					notifySourceSwitch();
				});
		if (tooltip != null) {
			entry.setTooltip(tooltip);
		}
		return entry.build();
	}

	/**
	 * One of the per-command chat switches (same notification as {@link #sourceToggle}).
	 * <p>
	 * The command is the name and the description is the tooltip, which is what the
	 * seventeenth feedback round asks for.
	 */
	private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> chatKindToggle(ConfigEntryBuilder eb,
			ModConfig config, ChatKind kind) {
		return eb.startBooleanToggle(Component.literal(kind.label()), config.isChatKindEnabled(kind))
				.setTooltip(Component.literal(kind.tooltip()))
				.setSaveConsumer(value -> {
					config.setChatKindEnabled(kind, value);
					notifySourceSwitch();
				})
				.build();
	}

	/**
	 * Rebuilds everything after a per-source switch changed (seventeenth feedback round,
	 * 2.3).
	 * <p>
	 * This is deliberately the <em>same</em> call the master switch makes
	 * ({@code AITranslateModClient.onConfigChanged}): it asks the refresh coordinator for a
	 * forced refresh, which bumps the render generation and rebuilds the caches that bake
	 * their text, so a source that was switched off shows its original text immediately -
	 * even when its translation is already in the cache. The translations are never
	 * dropped, so switching the source back on is instant and costs no request.
	 */
	static void notifySourceSwitch() {
		AITranslateModClient.onConfigChanged();
	}

	// ---------------------------------------------------------- 缓存管理

	private static void buildCache(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("缓存管理"));

		// Where the player is and how much is cached there, at the top of the page.
		// Sixteenth feedback round: the page is 当前存档 (top) → 缓存列表 (middle) →
		// 高级设置 (bottom). The current world is its own block with all four pieces of
		// information the document asks for (name, entries, disk usage, last update) and
		// the two actions that only make sense for the world that is open.
		boolean hasContext = AITranslateModClient.cacheManager != null
				&& AITranslateModClient.cacheManager.currentContext() != null;
		category.addEntry(new MultiActionEntry(Component.literal("当前缓存"), List.of(
				new MultiActionEntry.Action(Component.literal("导出"), ConfigScreen::exportCurrentCache,
						Component.literal("导出当前存档 / 服务器缓存"), () -> hasContext),
				new MultiActionEntry.Action(Component.literal("导入"), ConfigScreen::importCurrentCache,
						Component.literal("将缓存文件合并进当前存档 / 服务器"), () -> hasContext),
				new MultiActionEntry.Action(Component.literal("清理"), ConfigScreen::clearCurrent,
						Component.literal("只清空当前存档 / 服务器的缓存条目，条目保留在列表里"), () -> hasContext)),
				Component.literal((hasContext ? contextName() : "请进入存档") + "  ·  " + cacheSize() + " 条  ·  "
						+ FileUtil.humanSize(currentContextSize()) + "  ·  " + currentLastUpdated()),
				34));

		SubCategoryBuilder list = collapsed(eb, "缓存列表");
		List<CacheManager.ContextStats> stats = contexts();
		boolean otherCacheListed = false;
		if (!stats.isEmpty()) {
			for (CacheManager.ContextStats stat : stats) {
				// Every world is one row with 导出 / 导入 / 清理 (sixteenth feedback round;
				// the third button was called 删除 in the fifteenth). The tooltip says what
				// it will do for the world that is open, because that is the one row whose
				// action is not simply "remove the entry".
				boolean current = AITranslateModClient.cacheManager != null
						&& AITranslateModClient.cacheManager.isCurrent(stat.directory());
				if (current) continue;
				otherCacheListed = true;
				List<MultiActionEntry.Action> actions = new ArrayList<>();
				actions.add(new MultiActionEntry.Action(Component.literal("导出"),
						() -> exportContext(stat.directory()),
						Component.literal("导出为缓存根目录 exports/" + stat.directory().replace('/', '_')
								+ ".json（可再导入回来）")));
				actions.add(new MultiActionEntry.Action(Component.literal("导入"),
						() -> importIntoContext(stat.directory()),
						Component.literal("选择 JSON 文件并合并进这个存档（同一条目以更新的为准）")));
				actions.add(new MultiActionEntry.Action(Component.literal("清理"), () -> deleteContext(stat.directory()),
						Component.literal(current ? "清空当前存档的缓存（保留列表条目）"
								: "删除这个世界 / 服务器的缓存及其条目")));
				list.add(new MultiActionEntry(
						Component.literal(stat.name() + " [" + stat.type() + "]"),
						actions,
						Component.literal(stat.directory() + "  ·  " + stat.entries() + " 条  ·  "
								+ FileUtil.humanSize(stat.size()) + "  ·  " + lastUpdated(stat)),
						30));
			}
		}
		if (!otherCacheListed) list.add(description(eb, hasContext ? "（暂无其他存档缓存）" : "（暂无存档缓存）"));
		category.addEntry(list.build());

		SubCategoryBuilder advanced = collapsed(eb, "高级设置");
		advanced.add(new FullWidthStringEntry(Component.literal("缓存根目录"), config.cacheRoot,
				() -> "config/ai_translate/cache", value -> config.cacheRoot = value, false,
				Component.literal("相对路径基于 .minecraft")));
		advanced.add(new ActionEntry(Component.literal("打开缓存文件夹"), Component.literal("打开"),
				ConfigScreen::openCacheFolder, Component.literal("用系统文件管理器打开")));
		SubCategoryBuilder cleaning = collapsed(eb, "清理缓存");
		cleaning.add(new ActionEntry(Component.literal("清理孤儿缓存"), Component.literal("清理"),
				ConfigScreen::cleanupOrphans, Component.literal("已不存在的存档 / 服务器留下的缓存")));
		cleaning.add(new ActionEntry(Component.literal("清理全部缓存"), Component.literal("全部清空"),
				ConfigScreen::clearAll, Component.literal("不可撤销")));
		advanced.add(cleaning.build());
		category.addEntry(advanced.build());
	}

	// ---------------------------------------------------------- 高级设置
	// ---------------------------------------------------------- 高级设置

	/**
	 * The 高级设置 page (seventeenth feedback round).
	 * <p>
	 * Structure: API 相关 / 翻译调度 / 缓存相关.
	 * The document's removals are in here:
	 * <ul>
	 * <li>翻译完成提示 - control removed (the toast stays available in the config file,
	 * default off);</li>
	 * <li>开发期自检、假翻译与逐源调试开关不出现在正式版界面；</li>
	 * <li>the per-entry reset arrows are gone as well: no entry sets a default value any
	 * more, so Cloth does not draw one. 恢复默认设置 is the only reset.</li>
	 * </ul>
	 */
	private static void buildAdvanced(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("高级设置"));

		SubCategoryBuilder api = collapsed(eb, "API 相关");
		api.add(eb.startBooleanToggle(Component.literal("连续失败后自动关闭翻译"), config.autoDisableOnApiFailure)
				.setSaveConsumer(value -> config.autoDisableOnApiFailure = value)
				.build());
		api.add(eb.startIntSlider(Component.literal("连续失败多少次才提示"), config.failureThreshold, 1, 20)
				.setSaveConsumer(value -> config.failureThreshold = value).build());
		api.add(eb.startIntSlider(Component.literal("超时（秒，基础值）"), config.requestTimeoutSeconds, 5, 120)
				.setSaveConsumer(value -> config.requestTimeoutSeconds = value).build());
		api.add(eb.startIntSlider(Component.literal("超时上限（秒，长文本）"), config.longRequestTimeoutSeconds,
				30, 600)
				.setSaveConsumer(value -> config.longRequestTimeoutSeconds = value).build());
		api.add(eb.startIntSlider(Component.literal("每请求重试次数"), config.maxRetries, 0, 5)
				.setSaveConsumer(value -> config.maxRetries = value).build());
		category.addEntry(api.build());

		SubCategoryBuilder dispatch = collapsed(eb, "翻译调度");
		dispatch.add(eb.startBooleanToggle(Component.literal("启用世界文本范围检测"), config.enableRangeDetection)
				.setTooltip(Component.literal("只限制告示牌、实体名称和展示实体；界面、聊天与 HUD 不受影响"))
				.setSaveConsumer(value -> config.enableRangeDetection = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("世界文本翻译半径（格）"), config.translationRangeBlocks,
				8, 256).setSaveConsumer(value -> config.translationRangeBlocks = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("待处理队列上限"), config.maxPendingTranslations,
				100, 5000).setSaveConsumer(value -> config.maxPendingTranslations = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("批量大小（条）"), config.maxBatchSize, 1, 50)
				.setSaveConsumer(value -> config.maxBatchSize = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("单次请求字符上限"), config.maxBatchChars, 200, 4000)
				.setSaveConsumer(value -> config.maxBatchChars = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("单条文本拆分阈值"), config.maxTextSegmentChars, 80, 2000)
				.setSaveConsumer(value -> config.maxTextSegmentChars = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("请求间隔（毫秒）"), config.batchIntervalMs, 0, 2000)
				.setSaveConsumer(value -> config.batchIntervalMs = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("每秒请求上限"), config.maxRequestsPerSecond, 1, 30)
				.setSaveConsumer(value -> config.maxRequestsPerSecond = value).build());
		dispatch.add(eb.startIntSlider(Component.literal("HTTP 线程数"), config.httpThreads, 1, 8)
				.setSaveConsumer(value -> config.httpThreads = value).build());
		category.addEntry(dispatch.build());

		SubCategoryBuilder cache = collapsed(eb, "缓存相关");
		cache.add(eb.startIntSlider(Component.literal("内存缓存条目上限"), config.memoryCacheSize, 500, 20000)
				.setSaveConsumer(value -> config.memoryCacheSize = value).build());
		cache.add(eb.startBooleanToggle(Component.literal("刷新时清空内存缓存"), config.refreshClearsMemoryCache)
				.setSaveConsumer(value -> config.refreshClearsMemoryCache = value).build());
		cache.add(eb.startBooleanToggle(Component.literal("定期输出性能统计"), config.logPerformanceStats)
				.setSaveConsumer(value -> config.logPerformanceStats = value).build());
		category.addEntry(cache.build());

		category.addEntry(new ActionEntry(Component.literal("恢复默认设置"), Component.literal("恢复默认"),
				ConfigScreen::confirmReset,
				Component.literal("整套设置恢复默认值（会二次确认）")));
	}
	/** A collapsed (++) sub-category, which is the default for everything optional. */
	private static SubCategoryBuilder collapsed(ConfigEntryBuilder eb, String title) {
		return eb.startSubCategory(Component.literal(title)).setExpanded(false);
	}

	// ------------------------------------------------------------- 操作实现

	/**
	 * Runs the connection test and shows the result as the dot on the API page - no
	 * chat message, as the thirteenth feedback round asks. Chat is covered by the
	 * config screen anyway, so a chat line would be invisible exactly when it is
	 * needed.
	 *
	 * @return the asynchronous connection-test result
	 */
	public static java.util.concurrent.CompletableFuture<String> testConnection() {
		ModConfig config = AITranslateModClient.config;
		return testConnection(config, config == null ? "" : config.model,
				config == null ? "" : config.apiBaseUrl, config == null ? "" : config.apiKey);
	}

	// ---------------------------------------------------------- 词典

	private static void buildDictionary(ConfigBuilder builder, ConfigEntryBuilder eb, ModConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("词典管理"));
		var manager = AITranslateModClient.dictionaryManager;
		int current = manager == null ? 0 : manager.entries(
				com.aitranslate.client.dictionary.DictionaryManager.Scope.CURRENT).size();
		int global = manager == null ? 0 : manager.entries(
				com.aitranslate.client.dictionary.DictionaryManager.Scope.GLOBAL).size();
		boolean inWorld = manager != null && manager.hasCurrentContext();
		category.addEntry(new ActionEntry(Component.literal("当前词典（" + current + " 条）"), Component.literal("管理"),
				() -> openDictionary(com.aitranslate.client.dictionary.DictionaryManager.Scope.CURRENT),
				Component.literal(inWorld ? "只对当前世界或服务器生效" : "仅存档内可用"), () -> inWorld));
		category.addEntry(new ActionEntry(Component.literal("全局词典（" + global + " 条）"), Component.literal("管理"),
				() -> openDictionary(com.aitranslate.client.dictionary.DictionaryManager.Scope.GLOBAL),
				Component.literal("对所有世界和服务器生效")));
		category.addEntry(eb.startBooleanToggle(Component.literal("区分大小写"), config.dictionaryCaseSensitive)
				.setTooltip(Component.literal("默认关闭；Hello 与 hello 视为同一词条"))
				.setSaveConsumer(value -> config.dictionaryCaseSensitive = value).build());

		SubCategoryBuilder dictionaryList = collapsed(eb, "词典列表");
		var stored = manager == null ? List.<com.aitranslate.client.dictionary.DictionaryManager.ContextStats>of()
				: manager.listContexts();
		boolean listed = false;
		for (var stat : stored) {
			if (manager.isCurrent(stat.directory())) continue;
			listed = true;
			List<MultiActionEntry.Action> actions = List.of(
					new MultiActionEntry.Action(Component.literal("管理"), () -> openDictionary(stat.directory()),
							Component.literal("查看、编辑、删除或导入导出这个词典")),
					new MultiActionEntry.Action(Component.literal("清理"), () -> clearDictionary(stat.directory()),
							Component.literal("清空这个存档的全部词条")));
			dictionaryList.add(new MultiActionEntry(Component.literal(stat.name() + " [" + stat.type() + "]"), actions,
					Component.literal(stat.directory() + "  ·  " + stat.entries() + " 条  ·  "
							+ FileUtil.humanSize(stat.size()) + "  ·  " + formatTime(stat.lastUpdated())), 30));
		}
		if (!listed) dictionaryList.add(description(eb, "（暂无其他存档词典）"));
		category.addEntry(dictionaryList.build());

		SubCategoryBuilder advanced = collapsed(eb, "高级设置");
		advanced.add(new FullWidthStringEntry(Component.literal("词典根目录"), config.dictionaryRoot,
				() -> "config/ai_translate/dictionary", value -> config.dictionaryRoot = value, false,
				Component.literal("相对路径基于 .minecraft；修改后原目录数据不会自动搬移")));
		advanced.add(new ActionEntry(Component.literal("打开词典文件夹"), Component.literal("打开"),
				ConfigScreen::openDictionaryFolder, Component.literal("用系统文件管理器打开")));
		SubCategoryBuilder cleaning = collapsed(eb, "清理词典");
		cleaning.add(new ActionEntry(Component.literal("清理当前词典"), Component.literal("清理"),
				() -> confirmDictionaryClear(false), Component.literal("需要二次确认；只清理当前世界或服务器"),
				() -> AITranslateModClient.dictionaryManager != null
						&& AITranslateModClient.dictionaryManager.hasCurrentContext()));
		cleaning.add(new ActionEntry(Component.literal("清理全部词典"), Component.literal("全部清理"),
				() -> confirmDictionaryClear(true), Component.literal("需要二次确认；不会删除普通翻译缓存")));
		advanced.add(cleaning.build());
		category.addEntry(advanced.build());
	}

	private static void openDictionary(com.aitranslate.client.dictionary.DictionaryManager.Scope scope) {
		Minecraft client = Minecraft.getInstance();
		client.gui.setScreen(new DictionaryScreen(client.gui.screen(), scope));
	}

	private static void openDictionary(String directory) {
		Minecraft client = Minecraft.getInstance();
		client.gui.setScreen(new DictionaryScreen(client.gui.screen(), directory));
	}

	private static void clearDictionary(String directory) {
		if (AITranslateModClient.dictionaryManager != null) {
			AITranslateModClient.dictionaryManager.deleteContext(directory);
			message("已清理词典 " + directory);
			refreshCachePage();
		}
	}

	private static void confirmDictionaryClear(boolean all) {
		Minecraft client = Minecraft.getInstance();
		client.gui.setScreen(new DictionaryCleanupScreen(client.gui.screen(), all));
	}

	private static void openDictionaryFolder() {
		try {
			java.nio.file.Path root = AITranslateModClient.dictionaryRootDir().toAbsolutePath();
			java.nio.file.Files.createDirectories(root);
			net.minecraft.util.Util.getPlatform().openPath(root);
			toast("AI 翻译", "已打开 " + root);
		} catch (Exception e) {
			toast("AI 翻译：无法打开目录", String.valueOf(e.getMessage()));
			LOGGER.warn("Could not open the dictionary folder", e);
		}
	}

	static java.util.concurrent.CompletableFuture<String> testConnection(ModConfig current, String model,
			String apiBaseUrl, String apiKey) {
		long sequence = API_TEST_SEQUENCE.incrementAndGet();
		apiStatus = StatusDotEntry.State.TESTING;
		if (current == null) {
			apiStatus = StatusDotEntry.State.FAILED;
			return java.util.concurrent.CompletableFuture.completedFuture("连接失败: 配置未初始化");
		}
		ModConfig candidate = connectionTestConfig(current, model, apiBaseUrl, apiKey);
		var manager = new com.aitranslate.client.provider.ProviderManager(candidate);
		return manager.testConnection().handle((result, error) -> {
			String message = error == null ? result : "连接失败: " + String.valueOf(error.getMessage());
			if (sequence == API_TEST_SEQUENCE.get()) {
				boolean ok = message != null && message.startsWith("连接成功");
				apiStatus = ok ? StatusDotEntry.State.OK : StatusDotEntry.State.FAILED;
			}
			LOGGER.info("API connection test: {}", message);
			return message;
		}).whenComplete((result, error) -> manager.close());
	}

	private static ModConfig connectionTestConfig(ModConfig current, String model, String apiBaseUrl, String apiKey) {
		ModConfig candidate = new ModConfig();
		candidate.model = model == null ? "" : model.trim();
		candidate.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
		candidate.apiKey = apiKey == null ? "" : apiKey.trim();
		candidate.sourceLang = current.sourceLang;
		candidate.targetLang = current.targetLang;
		candidate.requestTimeoutSeconds = current.requestTimeoutSeconds;
		candidate.longRequestTimeoutSeconds = current.longRequestTimeoutSeconds;
		candidate.maxRetries = current.maxRetries;
		candidate.httpThreads = current.httpThreads;
		return candidate;
	}

	/**
	 * The "重新加载" action of the cache page: reads the bucket back from disk, tells
	 * the render layer and rebuilds the page in place.
	 * <p>
	 * Also refreshes the cache page in place.
	 */
	private static void clearCurrent() {
		if (AITranslateModClient.cacheManager != null) {
			AITranslateModClient.cacheManager.clearCurrent();
			AITranslateModClient.refreshRenderCaches();
			message("已清空当前存档缓存（条目保留）");
			refreshCachePage();
		}
	}

	private static void clearAll() {
		if (AITranslateModClient.cacheManager != null) {
			AITranslateModClient.cacheManager.clearAll();
			message("已清空全部缓存");
			refreshCachePage();
		}
	}

	/**
	 * Deletes one row.
	 * <p>
	 * Fifteenth feedback round: the world that is open is only <em>emptied</em> - its
	 * row stays in the list with 0 entries (the game is still writing to that bucket,
	 * and a deleted directory would just come back on the next save) - while any other
	 * world is removed from the list entirely, which is what the feedback document
	 * asks for.
	 */
	private static void deleteContext(String directory) {
		CacheManager manager = AITranslateModClient.cacheManager;
		if (manager == null) {
			return;
		}
		boolean current = manager.isCurrent(directory);
		manager.deleteContext(directory);
		if (current) {
			AITranslateModClient.refreshRenderCaches();
			message("已清空当前存档缓存，条目保留在列表里");
		} else {
			message("已删除 " + directory);
		}
		refreshCachePage();
	}

	/** Opens the drag screen for the in-screen button position (fifteenth round). */
	private static void openPositionScreen() {
		Minecraft client = Minecraft.getInstance();
		client.gui.setScreen(new com.aitranslate.client.display.ButtonPositionScreen(client.gui.screen()));
	}

	/**
	 * Opens the cache folder in the system file manager.
	 * <p>
	 * The old implementation used {@code java.awt.Desktop}, which is unavailable in a
	 * lot of Minecraft setups and then only printed a chat line behind the config
	 * screen - so the button looked dead. The game's own platform helper is used now
	 * and the result is reported as a toast, which stays visible above the config
	 * screen.
	 */
	private static void openCacheFolder() {
		if (AITranslateModClient.cacheManager == null) {
			toast("AI 翻译", "缓存管理器未初始化");
			return;
		}
		try {
			java.nio.file.Path root = AITranslateModClient.cacheManager.getRootDir().toAbsolutePath();
			java.nio.file.Files.createDirectories(root);
			net.minecraft.util.Util.getPlatform().openPath(root);
			toast("AI 翻译", "已打开 " + root);
			LOGGER.info("Opened cache folder {}", root);
		} catch (Exception e) {
			toast("AI 翻译：无法打开目录", String.valueOf(e.getMessage()));
			LOGGER.warn("Could not open the cache folder", e);
		}
	}

	/**
	 * Exports one world as a JSON file (fifteenth feedback round).
	 * <p>
	 * The file keeps the world's own name inside it, so importing it again lands in
	 * the same world - and importing it into another world is possible too, because the
	 * import falls back to the file's single context.
	 */
	private static void exportContext(String directory) {
		CacheManager manager = AITranslateModClient.cacheManager;
		if (manager == null) {
			return;
		}
		try {
			OperationFeedback.progress("正在导出...");
			String name = directory.replace('/', '_');
			java.nio.file.Path target = manager.exportDirectory().resolve(name + ".json");
			manager.writeBundle(manager.exportContext(directory), target);
			OperationFeedback.success("导出", target.getFileName() + "（" + cacheSummary(manager, directory) + "）");
			LOGGER.info("Exported cache bucket {} to {}", directory, target);
		} catch (Exception e) {
			OperationFeedback.failure("导出", String.valueOf(e.getMessage()));
			LOGGER.warn("Could not export the cache bucket {}", directory, e);
		}
		refreshCachePage();
	}

	/**
	 * Imports a chosen JSON file into one world's cache (the row's 导入 button).
	 * <p>
	 * The file is picked on a screen of the mod's own (see
	 * {@link JsonFilePickerScreen}) instead of through an OS dialog: the dialog is
	 * missing on many setups and would block the render thread.
	 */
	private static void importIntoContext(String directory) {
		CacheManager manager = AITranslateModClient.cacheManager;
		if (manager == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		client.gui.setScreen(new JsonFilePickerScreen(client.gui.screen(), AITranslateModClient.storageRootDir(),
				manager.getRootDir(),
				JsonFilePickerScreen.FileKind.CACHE, file -> {
			try {
				OperationFeedback.progress("正在导入...");
				CacheManager.ImportReport report = manager.importInto(file, directory);
				AITranslateModClient.refreshRenderCaches();
				if (report.contexts() == 0) {
					OperationFeedback.failure("导入", file.getFileName() + " 里没有可合并的缓存");
				} else {
					OperationFeedback.success("导入", file.getFileName() + " → " + directory + "（" + report.describe() + "）");
				}
				LOGGER.info("Imported cache from {} into {}: {}", file, directory, report);
			} catch (Exception e) {
				OperationFeedback.failure("导入", String.valueOf(e.getMessage()));
				LOGGER.warn("Could not import {} into {}", file, directory, e);
			}
			client.gui.setScreen(create(lastParent));
		}));
	}

	/** "N 条" for the export/toast text of one world. */
	private static String cacheSummary(CacheManager manager, String directory) {
		for (CacheManager.ContextStats stat : manager.listContexts()) {
			if (stat.directory().equals(directory)) {
				return stat.entries() + " 条";
			}
		}
		return "0 条";
	}

	/**
	 * Rebuilds the config screen after a cache action, so the list, the size and the
	 * "last updated" columns show the new state without closing and reopening the
	 * config (fifteenth feedback round).
	 * <p>
	 * The page the player is on is kept: Cloth reads {@code selectedCategoryIndex}
	 * while it initialises, so the index is copied onto the fresh screen <em>before</em>
	 * it is shown. Pending edits are committed first ({@code saveAll}), because a
	 * rebuild replaces the widget tree - losing a half-typed API key would be a
	 * regression of the very bug this round fixes.
	 */
	private static void refreshCachePage() {
		Minecraft client = Minecraft.getInstance();
		Screen current = client.gui.screen();
		if (!(current instanceof me.shedaniel.clothconfig2.gui.AbstractConfigScreen cloth)) {
			return;
		}
		int page = cloth.selectedCategoryIndex;
		try {
			cloth.saveAll(true);
		} catch (RuntimeException e) {
			LOGGER.warn("Could not commit the config before refreshing the cache page", e);
		}
		Screen rebuilt = create(lastParent);
		if (rebuilt instanceof me.shedaniel.clothconfig2.gui.AbstractConfigScreen fresh) {
			fresh.selectedCategoryIndex = page;
		}
		client.gui.setScreen(rebuilt);
	}

	/** A toast stays visible while the chat is covered by the config screen. */
	private static void toast(String title, String body) {
		try {
			Minecraft client = Minecraft.getInstance();
			var toasts = client.gui.toastManager();
			if (toasts != null) {
				net.minecraft.client.gui.components.toasts.SystemToast.add(toasts, TOAST_ID,
						Component.literal(title), Component.literal(body));
			}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not show a toast", e);
		}
	}

	private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId TOAST_ID =
			new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(4000L);

	private static void cleanupOrphans() {
		if (AITranslateModClient.cacheManager == null) {
			return;
		}
		java.util.Set<String> known = new java.util.HashSet<>();
		if (AITranslateModClient.currentContext() != null) {
			known.add(AITranslateModClient.currentContext().directoryName());
		}
		int removed = AITranslateModClient.cacheManager.cleanOrphanContexts(known);
		toast("AI 翻译", "已清理 " + removed + " 个孤儿缓存");
		refreshCachePage();
	}

	private static void message(String text) {
		com.aitranslate.client.util.ChatFeedback.send(text);
	}

	// ------------------------------------------------------------- 辅助

	private static me.shedaniel.clothconfig2.api.AbstractConfigListEntry<?> description(ConfigEntryBuilder eb,
			String text) {
		return eb.startTextDescription(Component.literal(text)).build();
	}

	private static String contextName() {
		if (AITranslateModClient.currentContext() == null) {
			return "未进入世界";
		}
		var context = AITranslateModClient.currentContext();
		return (context.type() == com.aitranslate.client.cache.CacheContext.ContextType.SINGLEPLAYER ? "单机 · "
				: "服务器 · ") + context.identifier();
	}

	private static int cacheSize() {
		return AITranslateModClient.cacheManager == null ? 0 : AITranslateModClient.cacheManager.currentSize();
	}

	/** Disk usage of the cache bucket of the world that is open. */
	private static long currentContextSize() {
		CacheManager manager = AITranslateModClient.cacheManager;
		if (manager == null || manager.currentDirectory() == null) {
			return 0L;
		}
		return FileUtil.directorySize(manager.getRootDir().resolve(manager.currentDirectory()));
	}

	/** "最后更新" of the bucket of the world that is open (sixteenth round, 1.5). */
	private static String currentLastUpdated() {
		CacheManager manager = AITranslateModClient.cacheManager;
		if (manager == null || manager.currentDirectory() == null) {
			return "未进入世界";
		}
		for (CacheManager.ContextStats stat : manager.listContexts()) {
			if (stat.directory().equals(manager.currentDirectory())) {
				return lastUpdated(stat);
			}
		}
		return "尚未写入";
	}

	private static String lastUpdated(CacheManager.ContextStats stat) {
		return formatTime(stat.lastUpdated());
	}

	private static void exportCurrentCache() {
		CacheManager manager = AITranslateModClient.cacheManager;
		if (manager != null && manager.currentDirectory() != null) exportContext(manager.currentDirectory());
	}

	private static void importCurrentCache() {
		CacheManager manager = AITranslateModClient.cacheManager;
		if (manager != null && manager.currentDirectory() != null) importIntoContext(manager.currentDirectory());
	}


	private static String formatTime(long timestamp) {
		if (timestamp <= 0L) return "尚未写入";
		long delta = Math.max(0L, System.currentTimeMillis() - timestamp);
		if (delta < 60_000L) {
			return "刚刚更新";
		}
		if (delta < 3_600_000L) {
			return (delta / 60_000L) + " 分钟前更新";
		}
		if (delta < 86_400_000L) {
			return (delta / 3_600_000L) + " 小时前更新";
		}
		return (delta / 86_400_000L) + " 天前更新";
	}

	private static List<CacheManager.ContextStats> contexts() {
		return AITranslateModClient.cacheManager == null ? List.of()
				: AITranslateModClient.cacheManager.listContexts();
	}

}
