package com.aitranslate.client.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.aitranslate.client.util.FileUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Picks an importable cache or dictionary JSON below the mod's storage root.
 * <p>
 * There is no OS file dialog here on purpose: {@code java.awt} is missing in a fair
 * number of Minecraft setups (that is what made the old "open cache folder" button
 * look dead), and a dialog would also block the render thread. Instead the screen
 * lists only matching files under {@code config/ai_translate}; parent navigation and
 * symlink traversal can never leave that root.
 *
 * The list is paged (上一页 / 下一页) rather than scrolled: a page of rows with
 * stable button positions is easier to hit than a list whose rows move, and the page
 * count is shown so a long folder is obviously long.
 */
public class JsonFilePickerScreen extends Screen {
	public enum FileKind {
		CACHE("缓存"), DICTIONARY("词典");
		private final String label;
		FileKind(String label) { this.label = label; }
	}

	private static final int ROWS_PER_PAGE = 9;
	private static final int ROW_HEIGHT = 20;
	private static final int ROW_GAP = 2;

	private final Screen parent;
	private final Consumer<Path> onPick;
	private final FileKind kind;
	private final Path rootDirectory;

	private Path directory;
	private List<Path> entries = List.of();
	private int page;

	private Button upButton;
	private Button refreshButton;
	private Button pagePrev;
	private Button pageNext;

	public JsonFilePickerScreen(Screen parent, Path rootDirectory, Path startDirectory, FileKind kind,
			Consumer<Path> onPick) {
		super(Component.literal("选择要导入的" + kind.label + "文件"));
		this.parent = parent;
		this.onPick = onPick;
		this.kind = kind;
		this.rootDirectory = (rootDirectory == null ? Path.of(".") : rootDirectory).toAbsolutePath().normalize();
		Path requested = (startDirectory == null ? this.rootDirectory : startDirectory).toAbsolutePath().normalize();
		this.directory = withinRoot(requested) ? requested : this.rootDirectory;
	}

	@Override
	protected void init() {
		try {
			Files.createDirectories(rootDirectory);
		} catch (IOException ignored) {
			// The empty-state message remains usable if the configured path is invalid.
		}
		entries = list(directory);
		int top = 24;
		int buttonWidth = Math.min(320, width - 80);
		int x = (width - buttonWidth) / 2;

		int shown = Math.max(0, Math.min(ROWS_PER_PAGE, entries.size() - page * ROWS_PER_PAGE));
		for (int i = 0; i < shown; i++) {
			Path entry = entries.get(page * ROWS_PER_PAGE + i);
			boolean dir = Files.isDirectory(entry);
			Button row = Button.builder(rowLabel(entry, dir), pressed -> open(entry))
					.bounds(x, top + i * (ROW_HEIGHT + ROW_GAP), buttonWidth, ROW_HEIGHT)
					.build();
			row.setTooltip(Tooltip.create(Component.literal(entry.toAbsolutePath().toString())));
			addRenderableWidget(row);
		}

		int footer = height - 28;
		int gap = ConfigUiMetrics.GAP;
		int small = Math.min(ConfigUiMetrics.SMALL_BUTTON_WIDTH,
				Math.max(42, (buttonWidth - gap * 4) / 5));
		upButton = addRenderableWidget(Button.builder(Component.literal("返回上级"), pressed -> {
			Path parentDir = directory.getParent();
			if (parentDir != null && withinRoot(parentDir)) {
				navigate(parentDir);
			}
		}).bounds(x, footer, small, 20).build());
		refreshButton = addRenderableWidget(Button.builder(Component.literal("刷新"), pressed -> navigate(directory))
				.bounds(x + small + gap, footer, small, 20).build());
		pagePrev = addRenderableWidget(Button.builder(Component.literal("上一页"), pressed -> {
			if (page > 0) {
				page--;
				rebuildWidgets();
			}
		}).bounds(x + (small + gap) * 2, footer, small, 20).build());
		pageNext = addRenderableWidget(Button.builder(Component.literal("下一页"), pressed -> {
			if (page + 1 < pageCount()) {
				page++;
				rebuildWidgets();
			}
		}).bounds(x + (small + gap) * 3, footer, small, 20).build());
		addRenderableWidget(Button.builder(Component.literal("取消"), pressed -> back())
				.bounds(x + (small + gap) * 4, footer, small, 20).build());

		updateButtons();
	}

	private void updateButtons() {
		upButton.active = !directory.equals(rootDirectory) && directory.getParent() != null
				&& withinRoot(directory.getParent());
		refreshButton.active = Files.isDirectory(directory);
		pagePrev.active = page > 0;
		pageNext.active = page + 1 < pageCount();
	}

	private int pageCount() {
		return Math.max(1, (entries.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
	}

	private static Component rowLabel(Path entry, boolean directory) {
		String name = entry.getFileName() == null ? entry.toString() : entry.getFileName().toString();
		if (directory) {
			return Component.literal("[目录] " + name);
		}
		return Component.literal(name + "  (" + sizeOf(entry) + ")");
	}

	private static String sizeOf(Path file) {
		try {
			return com.aitranslate.client.util.FileUtil.humanSize(Files.size(file));
		} catch (IOException e) {
			return "?";
		}
	}

	/** Directories first, then files, each sorted by name. */
	private List<Path> list(Path dir) {
		if (dir == null || !Files.isDirectory(dir)) {
			return List.of();
		}
		List<Path> found = new ArrayList<>();
		try (Stream<Path> stream = Files.list(dir)) {
			for (Path path : stream.toList()) {
				if (Files.isDirectory(path) && withinRoot(path)) {
					found.add(path);
				} else if (accepts(path)) {
					found.add(path);
				}
			}
		} catch (IOException e) {
			return List.of();
		}
		found.sort(Comparator
				.comparing((Path path) -> !Files.isDirectory(path))
				.thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
		return found;
	}

	private static boolean isJson(Path path) {
		return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
	}

	/** Content-based filtering prevents cache and dictionary exports from mixing. */
	private boolean accepts(Path path) {
		if (!withinRoot(path) || !isJson(path) || !Files.isRegularFile(path)) return false;
		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (kind == FileKind.DICTIONARY && !fileName.contains("dictionary")) return false;
		try {
			JsonElement parsed = FileUtil.readJson(path);
			if (!parsed.isJsonObject()) return false;
			JsonObject root = parsed.getAsJsonObject();
			boolean dictionary = isDictionary(root);
			return kind == FileKind.DICTIONARY ? dictionary : !dictionary && isCache(root);
		} catch (IOException | RuntimeException ignored) {
			return false;
		}
	}

	private static boolean isDictionary(JsonObject root) {
		if (!root.has("entries") || !root.get("entries").isJsonArray()) return false;
		for (JsonElement element : root.getAsJsonArray("entries")) {
			if (element.isJsonObject() && element.getAsJsonObject().has("original")
					&& element.getAsJsonObject().has("translated")) return true;
		}
		// An empty exported dictionary is valid too; its format uses formatVersion.
		return root.has("formatVersion");
	}

	private static boolean isCache(JsonObject root) {
		if (root.has("format") && "ai-translate-cache".equals(root.get("format").getAsString())) return true;
		if (root.has("contexts") && root.get("contexts").isJsonObject()) return true;
		if (root.has("entries") && root.get("entries").isJsonObject()) return true;
		// Legacy flat cache: at least one object value looks like a cache entry.
		for (var entry : root.entrySet()) {
			if (entry.getValue().isJsonObject()) {
				JsonObject value = entry.getValue().getAsJsonObject();
				if (value.has("translated") || value.has("translation") || value.has("original")) return true;
			}
		}
		return false;
	}

	private void navigate(Path dir) {
		Path normalized = dir.toAbsolutePath().normalize();
		if (!withinRoot(normalized) || !Files.isDirectory(normalized)) return;
		directory = normalized;
		page = 0;
		entries = list(normalized);
		rebuildWidgets();
	}

	private void open(Path entry) {
		if (Files.isDirectory(entry)) {
			navigate(entry);
		} else {
			pick(entry);
		}
	}

	private void pick(Path file) {
		if (!accepts(file)) return;
		setScreen(null);
		Consumer<Path> callback = onPick;
		if (callback != null) {
			callback.accept(file);
		}
	}

	private void back() {
		setScreen(parent);
	}

	private void setScreen(Screen screen) {
		Minecraft.getInstance().gui.setScreen(screen);
	}

	private boolean withinRoot(Path candidate) {
		if (candidate == null) return false;
		Path normalized = candidate.toAbsolutePath().normalize();
		if (!normalized.startsWith(rootDirectory)) return false;
		try {
			if (Files.exists(normalized) && Files.exists(rootDirectory)) {
				return normalized.toRealPath().startsWith(rootDirectory.toRealPath());
			}
		} catch (IOException e) {
			return false;
		}
		return true;
	}

	@Override
	public void onClose() {
		back();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		extractor.fill(0, 0, width, height, 0x80000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(extractor, mouseX, mouseY, partialTick);
		var font = Minecraft.getInstance().font;
		String folder = directory == null ? "-" : directory.toAbsolutePath().toString();
		extractor.centeredText(font,
				kind.label + "目录：" + folder + "（" + entries.size() + " 项，第 " + (page + 1) + "/" + pageCount() + " 页）",
				width / 2, 2, 0xFFFFFFFF);
		if (entries.isEmpty()) {
			extractor.centeredText(font, "这里没有可导入的" + kind.label + "文件，可先用“导出”生成一个",
					width / 2, height / 2, 0xFFA0A0A0);
		}
		extractor.centeredText(font, "限制范围：" + rootDirectory,
				width / 2, height - 40, 0xFF909090);
	}
}
