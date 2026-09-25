package com.aitranslate.client.config;

import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.dictionary.DictionaryEntry;
import com.aitranslate.client.dictionary.DictionaryInputs;
import com.aitranslate.client.dictionary.DictionaryManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Searchable editor for current-world and global fixed translations. */
public final class DictionaryScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private final Screen parent;
	private DictionaryManager.Scope scope;
	private final String contextDirectory;
	private EditBox searchBox, originalBox, translatedBox, sourceBox, targetBox;
	private String search = "";
	private int page;
	private DictionaryEntry editing;
	private String status = "";

	public DictionaryScreen(Screen parent, DictionaryManager.Scope scope) {
		super(Component.literal("AI Translate 词典"));
		this.parent = parent;
		this.contextDirectory = null;
		this.scope = scope == null ? DictionaryManager.Scope.CURRENT : scope;
		if (this.scope == DictionaryManager.Scope.CURRENT && (AITranslateModClient.dictionaryManager == null
				|| !AITranslateModClient.dictionaryManager.hasCurrentContext())) {
			this.scope = DictionaryManager.Scope.GLOBAL;
		}
	}

	/** Opens a stored world/server dictionary that is not the active context. */
	public DictionaryScreen(Screen parent, String contextDirectory) {
		super(Component.literal("AI Translate 词典"));
		this.parent = parent;
		this.scope = DictionaryManager.Scope.CURRENT;
		this.contextDirectory = contextDirectory;
	}

	@Override
	protected void init() {
		int margin = 12;
		int contentWidth = Math.min(620, width - margin * 2);
		int x = (width - contentWidth) / 2;
		int half = (contentWidth - 6) / 2;
		int standard = ConfigUiMetrics.STANDARD_BUTTON_WIDTH;
		int small = ConfigUiMetrics.SMALL_BUTTON_WIDTH;
		int gap = ConfigUiMetrics.GAP;

		boolean hasCurrent = AITranslateModClient.dictionaryManager != null
				&& AITranslateModClient.dictionaryManager.hasCurrentContext();
		if (contextDirectory == null) {
			Button currentTab = addRenderableWidget(Button.builder(Component.literal("当前词典"),
					b -> switchScope(DictionaryManager.Scope.CURRENT)).bounds(x, 20, standard, 20).build());
			currentTab.setTooltip(Tooltip.create(Component.literal(hasCurrent ? "只对当前世界或服务器生效" : "仅存档内可用")));
			currentTab.active = hasCurrent && scope != DictionaryManager.Scope.CURRENT;
			addRenderableWidget(Button.builder(Component.literal("全局词典"), b -> switchScope(DictionaryManager.Scope.GLOBAL))
					.bounds(x + standard + gap, 20, standard, 20).build()).active = scope != DictionaryManager.Scope.GLOBAL;
		}
		addRenderableWidget(Button.builder(Component.literal("上一页"), b -> changePage(-1))
				.bounds(x + contentWidth - small * 2 - gap, 20, small, 20).build()).active = page > 0;
		addRenderableWidget(Button.builder(Component.literal("下一页"), b -> changePage(1))
				.bounds(x + contentWidth - small, 20, small, 20).build()).active = page + 1 < pageCount();

		searchBox = new EditBox(Minecraft.getInstance().font, x, 44, contentWidth - small - gap, 18, Component.literal("搜索"));
		searchBox.setMaxLength(256);
		searchBox.setValue(search);
		searchBox.setHint(Component.literal("按原文或译文搜索"));
		addRenderableWidget(searchBox);
		addRenderableWidget(Button.builder(Component.literal("搜索"), b -> applySearch())
				.bounds(x + contentWidth - small, 43, small, 20).build());

		List<DictionaryEntry> visible = filtered();
		int rows = rowsPerPage();
		int from = Math.min(visible.size(), page * rows);
		int to = Math.min(visible.size(), from + rows);
		for (int i = from; i < to; i++) {
			DictionaryEntry entry = visible.get(i);
			int y = 66 + (i - from) * (ROW_HEIGHT + 2);
			Button row = Button.builder(rowLabel(entry), b -> edit(entry))
					.bounds(x, y, contentWidth - small - gap, ROW_HEIGHT).build();
			row.setTooltip(Tooltip.create(Component.literal(entry.original() + " → " + entry.translated()
					+ "\n" + entry.sourceLang() + " → " + entry.targetLang())));
			addRenderableWidget(row);
			addRenderableWidget(Button.builder(Component.literal("删除"), b -> delete(entry))
					.bounds(x + contentWidth - small, y, small, ROW_HEIGHT).build());
		}

		int editorY = height - 72;
		originalBox = field(x, editorY, half, editing == null ? "" : editing.original(), "原文");
		originalBox.setTooltip(Tooltip.create(Component.literal("多个原文用英文分号 ; 分隔；用 \\; 输入分号本身")));
		translatedBox = field(x + half + 6, editorY, half, editing == null ? "" : editing.translated(), "译文");
		int languageWidth = Math.max(72, (contentWidth - 104) / 2);
		sourceBox = field(x, editorY + 22, languageWidth, editing == null ? sourceDefault() : editing.sourceLang(), "源语言");
		targetBox = field(x + languageWidth + 4, editorY + 22, languageWidth,
				editing == null ? targetDefault() : editing.targetLang(), "目标语言");
		addRenderableWidget(Button.builder(Component.literal(editing == null ? "新增" : "保存"), b -> saveEntry())
				.bounds(x + contentWidth - standard, editorY + 21, standard, 20).build());

		int footerY = height - 23;
		int fw = Math.min(standard, Math.max(52, (contentWidth - gap * 3) / 4));
		int footerX = x + (contentWidth - (fw * 4 + gap * 3)) / 2;
		addRenderableWidget(Button.builder(Component.literal("导入"), b -> importFile()).bounds(footerX, footerY, fw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("导出"), b -> exportFile()).bounds(footerX + fw + gap, footerY, fw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("取消编辑"), b -> edit(null))
				.bounds(footerX + (fw + gap) * 2, footerY, fw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
				.bounds(footerX + (fw + gap) * 3, footerY, fw, 20).build());
	}

	private EditBox field(int x, int y, int width, String value, String hint) {
		EditBox box = new EditBox(Minecraft.getInstance().font, x, y, width, 18, Component.literal(hint));
		box.setMaxLength(1024);
		box.setValue(value == null ? "" : value);
		box.setHint(Component.literal(hint));
		addRenderableWidget(box);
		return box;
	}

	private List<DictionaryEntry> filtered() {
		DictionaryManager manager = AITranslateModClient.dictionaryManager;
		if (manager == null) return List.of();
		String query = search.toLowerCase(Locale.ROOT);
		List<DictionaryEntry> source = contextDirectory == null ? manager.entries(scope) : manager.entries(contextDirectory);
		return source.stream().filter(e -> query.isBlank()
				|| e.original().toLowerCase(Locale.ROOT).contains(query)
				|| e.translated().toLowerCase(Locale.ROOT).contains(query)).toList();
	}

	private int rowsPerPage() { return Math.max(1, (height - 168) / (ROW_HEIGHT + 2)); }
	private int pageCount() { return Math.max(1, (filtered().size() + rowsPerPage() - 1) / rowsPerPage()); }
	private Component rowLabel(DictionaryEntry e) {
		return Component.literal(shorten(e.original(), 34) + "  →  " + shorten(e.translated(), 34));
	}
	private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }

	private void switchScope(DictionaryManager.Scope next) { scope = next; page = 0; editing = null; status = ""; rebuildWidgets(); }
	private void changePage(int delta) { page = Math.max(0, Math.min(pageCount() - 1, page + delta)); rebuildWidgets(); }
	private void applySearch() { search = searchBox.getValue().trim(); page = 0; rebuildWidgets(); }
	private void edit(DictionaryEntry entry) { editing = entry; rebuildWidgets(); }
	private void delete(DictionaryEntry entry) {
		if (contextDirectory == null) AITranslateModClient.dictionaryManager.remove(scope, entry);
		else AITranslateModClient.dictionaryManager.remove(contextDirectory, entry);
		status = "[AT] 已删除词条"; editing = null; rebuildWidgets();
	}

	private void saveEntry() {
		List<String> originals = DictionaryInputs.splitOriginals(originalBox.getValue());
		String translated = translatedBox.getValue() == null ? "" : translatedBox.getValue().trim();
		if (originals.isEmpty() || translated.isEmpty()) { status = "[AT] 原文和译文不能为空"; return; }
		if (contextDirectory == null && scope == DictionaryManager.Scope.CURRENT
				&& !AITranslateModClient.dictionaryManager.hasCurrentContext()) {
			status = "[AT] 仅存档内可用"; return;
		}
		if (editing != null) {
			if (contextDirectory == null) AITranslateModClient.dictionaryManager.remove(scope, editing);
			else AITranslateModClient.dictionaryManager.remove(contextDirectory, editing);
		}
		List<DictionaryEntry> values = originals.stream().map(original -> new DictionaryEntry(original, translated,
				sourceBox.getValue(), targetBox.getValue()).normalized()).toList();
		int count = contextDirectory == null ? AITranslateModClient.dictionaryManager.putAll(scope, values)
				: AITranslateModClient.dictionaryManager.putAll(contextDirectory, values);
		status = "[AT] 已创建 " + count + " 条词条";
		editing = null;
		rebuildWidgets();
	}

	private void importFile() {
		var manager = AITranslateModClient.dictionaryManager;
		Minecraft.getInstance().gui.setScreen(new JsonFilePickerScreen(this, AITranslateModClient.storageRootDir(),
				manager.importStartDirectory(),
				JsonFilePickerScreen.FileKind.DICTIONARY, file -> {
			OperationFeedback.progress("正在导入...");
			try {
				int count = contextDirectory == null ? manager.importFile(scope, file) : manager.importFile(contextDirectory, file);
				status = "[AT] 导入成功：" + count + " 条";
				OperationFeedback.success("导入", count + " 条词条");
			} catch (RuntimeException e) {
				status = "[AT] 导入失败：" + e.getMessage();
				OperationFeedback.failure("导入", e.getMessage());
			}
			Minecraft.getInstance().gui.setScreen(this);
		}));
	}

	private void exportFile() {
		OperationFeedback.progress("正在导出...");
		try {
			var target = contextDirectory == null ? AITranslateModClient.dictionaryManager.export(scope)
					: AITranslateModClient.dictionaryManager.exportContext(contextDirectory);
			if (Files.isRegularFile(target)) {
				status = "[AT] 导出成功：" + target.getFileName();
				OperationFeedback.success("导出", target.getFileName().toString());
			} else {
				status = "[AT] 导出失败";
				OperationFeedback.failure("导出", "文件未生成");
			}
		} catch (RuntimeException e) {
			status = "[AT] 导出失败：" + e.getMessage();
			OperationFeedback.failure("导出", e.getMessage());
		}
	}

	private String sourceDefault() { return AITranslateModClient.config == null ? "auto" : AITranslateModClient.config.sourceLang; }
	private String targetDefault() { return AITranslateModClient.config == null ? "zh_cn" : AITranslateModClient.config.targetLang; }

	@Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
	@Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float tick) {
		super.extractBackground(g, mx, my, tick); g.fill(0, 0, width, height, 0xA0000000);
	}
	@Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
		super.extractRenderState(g, mx, my, tick);
		String name = contextDirectory != null ? contextDirectory
				: scope == DictionaryManager.Scope.CURRENT ? "当前词典" : "全局词典";
		g.centeredText(Minecraft.getInstance().font, name + " · " + filtered().size() + " 条 · 第 " + (page + 1)
				+ "/" + pageCount() + " 页", width / 2, 7, 0xFFFFFFFF);
		if (!status.isBlank()) g.centeredText(Minecraft.getInstance().font, status, width / 2, height - 88, 0xFFFFD070);
	}
}
