package com.aitranslate.client.display;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.capture.ComponentExtractor;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.config.ModConfig;
import net.minecraft.network.chat.Component;

/**
 * Bridge between the mixins and the translation pipeline.
 * <p>
 * A mixin only has to hand the component it is about to render to
 * {@link #translated(Component, TextType)}. Everything else - the master
 * switch, the per-source switch, literal extraction, cache lookup, asynchronous
 * requests and the styling of the replacement - happens here.
 * <p>
 * The returned component is always safe to render: when translation is disabled,
 * nothing is cached yet or the text is not translatable, the original component
 * is returned unchanged. Game data is never touched, which is why the master
 * switch restores the original text instantly.
 */
public final class TranslationSupport {
	/**
	 * Components that were already built by this mod. Some sources pass a
	 * component through two hooked render calls (boss bar -> extractor, dialog
	 * body -> multi line label); those must not be translated twice.
	 * Identity based and bounded so it can never grow without limit.
	 */
	private static final java.util.Set<Component> DISPLAY_COMPONENTS =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private static final java.util.ArrayDeque<Component> DISPLAY_ORDER = new java.util.ArrayDeque<>();
	private static final int MAX_TRACKED = 512;

	private TranslationSupport() {
	}

	/** Marks a freshly built replacement component (see {@link #DISPLAY_COMPONENTS}). */
	public static Component markDisplay(Component component) {
		if (component == null) {
			return null;
		}
		synchronized (DISPLAY_COMPONENTS) {
			if (DISPLAY_COMPONENTS.add(component)) {
				DISPLAY_ORDER.addLast(component);
				while (DISPLAY_ORDER.size() > MAX_TRACKED) {
					DISPLAY_COMPONENTS.remove(DISPLAY_ORDER.removeFirst());
				}
			}
		}
		return component;
	}

	public static boolean isDisplay(Component component) {
		if (component == null) {
			return false;
		}
		synchronized (DISPLAY_COMPONENTS) {
			return DISPLAY_COMPONENTS.contains(component);
		}
	}

	public static boolean isActive(TextType type) {
		return isActive(type, true);
	}

	/**
	 * @param respectSourceSwitch {@code false} for sources that decide per text
	 *                            instead of per {@link TextType} - a chat line is
	 *                            translated according to which command produced it
	 *                            (thirteenth feedback round), so only the master
	 *                            switch and the API configuration matter there.
	 */
	private static boolean isActive(TextType type, boolean respectSourceSwitch) {
		ModConfig config = AITranslateModClient.config;
		if (config == null || AITranslateModClient.scheduler == null) {
			return false;
		}
		return respectSourceSwitch ? AITranslateModClient.scheduler.isEnabled(type)
				: AITranslateModClient.scheduler.isAvailable();
	}

	// ---------------------------------------------------------- generation

	/**
	 * Bumped whenever the rendered result can change: a new translation arrived, the
	 * master switch was flipped or the config was edited.
	 * <p>
	 * Several places in the game cache "already split / already built" text
	 * (sign render lines, text display lines, book page lines, dialog labels).
	 * Those caches are built once and would keep showing the original text
	 * forever, which is why the first version only translated a sign after it was
	 * edited and a book page only after paging. Each of those caches now compares
	 * this counter once per frame and rebuilds itself when it changed.
	 */
	private static final java.util.concurrent.atomic.AtomicLong GENERATION =
			new java.util.concurrent.atomic.AtomicLong(1L);

	public static long generation() {
		return GENERATION.get();
	}

	public static void bumpGeneration() {
		GENERATION.incrementAndGet();
	}

	/** Drops the per-component memo (used by the manual refresh key). */
	public static void clearRenderMemo() {
		RenderMemo.clear();
	}

	/**
	 * The component to render instead of {@code original}: the original with every
	 * hardcoded literal leaf replaced by its translation. Returns {@code original}
	 * itself when there is nothing to show yet, so the render position, scale,
	 * lighting and events of the game's own call are always preserved.
	 * <p>
	 * Results are memoised per source component identity together with the
	 * generation they were computed in, so a component that is rendered again
	 * unchanged (chat lines, sign lines, scoreboard rows, ...) costs one map
	 * lookup per frame instead of a tree walk.
	 */
	public static Component translated(Component original, TextType type) {
		return translated(original, type, null);
	}

	/**
	 * Same as {@link #translated(Component, TextType)} but with a hint about how
	 * urgent this text is (tenth feedback round).
	 * <p>
	 * The hint comes from hooks that know more than the text alone: the entity name
	 * tag and sign renderers know how far away the text is, so a name two blocks in
	 * front of the player is requested before a sign at the horizon. The crosshair
	 * still wins over the hint - what the player is pointing at is the focus.
	 */
	public static Component translated(Component original, TextType type,
			com.aitranslate.client.scheduler.TranslationPriority hint) {
		return translated(original, type, hint, null);
	}

	/**
	 * Same as {@link #translated(Component, TextType, com.aitranslate.client.scheduler.TranslationPriority)}
	 * with a label that names the source in the log (eleventh feedback round).
	 * <p>
	 * The book hook passes "book page 19/21", so a page that cannot be translated is
	 * named by its page number in the log instead of by a hash.
	 */
	public static Component translated(Component original, TextType type,
			com.aitranslate.client.scheduler.TranslationPriority hint, String label) {
		return translated(original, type, hint, label, true);
	}

	/**
	 * A chat line whose kind decides whether it is translated (thirteenth feedback
	 * round).
	 * <p>
	 * {@code /me}, {@code /say}, {@code /msg} and {@code /tellraw} output each have
	 * their own switch, so the generic chat switch must not veto a line the player
	 * explicitly asked for - or the other way round.
	 */
	public static Component translatedChat(Component original, com.aitranslate.client.capture.ChatKind kind) {
		String label = kind == com.aitranslate.client.capture.ChatKind.SCRIPT
				? "diagnostic:tellraw" : kind == null ? null : kind.label();
		return translated(original, TextType.CHAT, null, label, false);
	}

	private static Component translated(Component original, TextType type,
			com.aitranslate.client.scheduler.TranslationPriority hint, String label,
			boolean respectSourceSwitch) {
		if (original == null || isDisplay(original) || !isActive(type, respectSourceSwitch)
				|| hidesTranslations(type)) {
			return original;
		}
		long generation = GENERATION.get();
		RenderMemo.Entry memo = RenderMemo.get(original);
		if (memo != null && memo.generation() == generation) {
			return memo.result();
		}
		Map<String, String> translations = resolve(original, type, hint, label, respectSourceSwitch);
		Component result = ComponentExtractor.hasTranslation(original, translations)
				? DisplayComponentBuilder.translated(original, translations, AITranslateModClient.config)
				: original;
		if (isDiagnostic(label)) {
			com.aitranslate.client.util.DebugLog.once("trace:display:" + label + ":" + original.getString()
					+ "->" + result.getString(),
					"[trace:{}:7-component] constructed={} source='{}' result='{}' translations={}",
					label, result == original ? "original" : "translation", original.getString(), result.getString(),
					translations);
		}
		RenderMemo.put(original, generation, result);
		return result;
	}

	/**
	 * The in-screen "show the original" switch (thirteenth feedback round).
	 * <p>
	 * A player who opened a book or a dialog could not use the master switch key
	 * without leaving the screen first. {@code ScreenOriginalMode} remembers that
	 * choice per screen; this is the single place where it takes effect, so every
	 * screen-scoped source obeys it at once and no cache has to be thrown away -
	 * switching back is instant because the translations were never dropped.
	 */
	public static boolean hidesTranslations(TextType type) {
		return com.aitranslate.client.display.ScreenOriginalMode.hides(type);
	}

	/**
	 * Bounded identity memo for {@link #translated(Component, TextType)}.
	 * <p>
	 * Normally only touched from the render thread; the accessors are synchronized
	 * because one hook ({@code ItemStack#getTooltipLines}) can also run on other
	 * threads, and a corrupted IdentityHashMap would be a very unpleasant bug.
	 */
	private static final class RenderMemo {
		private static final int MAX = 512;
		private static final java.util.Map<Component, Entry> ENTRIES =
				new java.util.IdentityHashMap<>();
		private static final java.util.ArrayDeque<Component> ORDER = new java.util.ArrayDeque<>();

		private record Entry(long generation, Component result) {
		}

		private RenderMemo() {
		}

		static synchronized Entry get(Component component) {
			return ENTRIES.get(component);
		}

		static synchronized void put(Component component, long generation, Component result) {
			if (ENTRIES.put(component, new Entry(generation, result)) == null) {
				ORDER.addLast(component);
				while (ORDER.size() > MAX) {
					ENTRIES.remove(ORDER.removeFirst());
				}
			}
		}

		static synchronized void clear() {
			ENTRIES.clear();
			ORDER.clear();
		}
	}

	/** Same as {@link #translated(Component, TextType)} but reports whether a translation is shown. */
	public static Optional<Component> translatedOptional(Component original, TextType type) {
		Component result = translated(original, type);
		return result == original ? Optional.empty() : Optional.of(result);
	}

	/**
	 * Resolves cached translations and schedules asynchronous requests for the
	 * texts that are still missing. Rendering keeps showing the original until the
	 * callback invalidates the view.
	 * <p>
	 * Tenth feedback round: every request carries the priority of the current focus
	 * ({@link com.aitranslate.client.focus.FocusTracker}), so the text the player is
	 * looking at - the open book page, the hovered tooltip, the sign under the
	 * crosshair - is sent before the texts that merely happen to be rendered.
	 */
	public static Map<String, String> resolve(Component original, TextType type) {
		return resolve(original, type, null);
	}

	/** {@link #resolve(Component, TextType)} with a priority hint from the hook. */
	public static Map<String, String> resolve(Component original, TextType type,
			com.aitranslate.client.scheduler.TranslationPriority hint) {
		return resolve(original, type, hint, null);
	}

	/** {@link #resolve(Component, TextType, com.aitranslate.client.scheduler.TranslationPriority)} with a log label. */
	public static Map<String, String> resolve(Component original, TextType type,
			com.aitranslate.client.scheduler.TranslationPriority hint, String label) {
		return resolve(original, type, hint, label, true);
	}

	private static Map<String, String> resolve(Component original, TextType type,
			com.aitranslate.client.scheduler.TranslationPriority hint, String label,
			boolean respectSourceSwitch) {
		if (original == null || !isActive(type, respectSourceSwitch) || hidesTranslations(type)
				|| AITranslateModClient.scheduler == null) {
			return Map.of();
		}
		List<String> texts = ComponentExtractor.uniqueTexts(original);
		if (isDiagnostic(label)) {
			com.aitranslate.client.util.DebugLog.once("trace:extract:" + label + ":" + original.getString(),
					"[trace:{}:2-extractor] extracted={} tree={}", label, texts,
					com.aitranslate.client.util.ComponentDiagnostics.describe(original));
		}
		// Fast path: components that are rebuilt on every frame (the item name
		// overlay, tooltips) walk their leaves every frame, so the result map is only
		// allocated when something is actually cached - which is not the case while
		// the first request for this text is still in flight.
		Map<String, String> translations = null;
		for (String text : texts) {
			Optional<String> cached = AITranslateModClient.scheduler.cached(type, text);
			if (cached.isPresent()) {
				if (isDiagnostic(label)) {
					com.aitranslate.client.util.DebugLog.once("trace:cache-hit:" + label + ":" + text,
							"[trace:{}:4-cache] HIT source='{}' result='{}'", label, text, cached.get());
				}
				if (translations == null) {
					translations = new LinkedHashMap<>();
				}
				translations.put(text, cached.get());
			} else {
				if (isDiagnostic(label)) {
					com.aitranslate.client.util.DebugLog.once("trace:cache-miss:" + label + ":" + text,
							"[trace:{}:4-cache] MISS source='{}' skipReason={}", label, text,
							AITranslateModClient.scheduler.detector().skipReason(text));
				}
				// Distance/range changes invalidate render caches, never translation
				// caches. Once a text is cached, do not even enter the request path when
				// the player returns to it; the cached value is displayed immediately.
				com.aitranslate.client.scheduler.TranslationPriority priority =
						com.aitranslate.client.focus.FocusTracker.isFocusedText(text) || hint == null
								? com.aitranslate.client.focus.FocusTracker.priorityFor(type, text)
								: hint;
				Runnable onReady = type == TextType.CHAT
						? RefreshCoordinator::requestChatTranslationRefresh
						: null;
				AITranslateModClient.scheduler.request(type, text, priority, onReady, label);
			}
		}
		return translations == null ? Map.of() : translations;
	}

	private static boolean isDiagnostic(String label) {
		return label != null && label.startsWith("diagnostic:");
	}

	/** Schedules a batch of texts (used when a source knows all its texts up front). */
	public static void requestAll(TextType type, Collection<String> texts, Runnable onReady) {
		if (!isActive(type) || AITranslateModClient.scheduler == null) {
			return;
		}
		AITranslateModClient.scheduler.requestAll(type, texts, onReady);
	}

	/**
	 * Translation of a plain string. Some render paths in 26.1 take a
	 * {@code String} instead of a {@link Component} (the sign edit screen draws its
	 * lines that way), so the same cache lookup / request logic is offered for
	 * strings. The caller decides where the string comes from; nothing is written
	 * back into the game data.
	 */
	public static String translatedText(String text, TextType type) {
		if (text == null || text.isEmpty() || !isActive(type) || hidesTranslations(type)
				|| AITranslateModClient.scheduler == null) {
			return text;
		}
		String key = ComponentExtractor.translatableText(text);
		if (key == null || key.isBlank()) {
			return text;
		}
		Optional<String> cached = AITranslateModClient.scheduler.cached(type, key);
		if (cached.isPresent()) {
			// applyTranslation puts the symbols back when the text is wrapped in them
			// (twelfth feedback round) - the sign editor shows "<Death>", not "死亡".
			return ComponentExtractor.applyTranslation(text, Map.of(key, cached.get()));
		}
		AITranslateModClient.scheduler.request(type, key,
				com.aitranslate.client.focus.FocusTracker.priorityFor(type, key), null);
		return text;
	}

	/**
	 * Translation of one semantic block whose newlines are meaningful (a whole
	 * sign). Unlike {@link #translatedText}, the exact string is the cache key: it is
	 * not unwrapped or split into component leaves.
	 */
	public static String translatedBlockText(String text, TextType type,
			com.aitranslate.client.scheduler.TranslationPriority priority, String label) {
		if (text == null || text.isBlank() || !isActive(type) || hidesTranslations(type)
				|| AITranslateModClient.scheduler == null) {
			return text;
		}
		Optional<String> cached = AITranslateModClient.scheduler.cached(type, text);
		if (cached.isPresent()) {
			return cached.get();
		}
		AITranslateModClient.scheduler.request(type, text, priority, null, label);
		return text;
	}
}
