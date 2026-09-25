package com.aitranslate.client.display;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.config.ModConfig;
import com.aitranslate.client.mixin.ChatComponentAccessor;
import com.aitranslate.client.mixin.ScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.network.chat.Component;

/**
 * Turns "a translation arrived" into "the screen shows it", without ever
 * rebuilding more than once per action.
 * <p>
 * Substituting a {@link Component} at render time is not enough on its own: a lot
 * of Minecraft UI builds its lines once and then keeps them (chat lines, sign
 * lines, book pages, dialog labels, item name caches). Writing the translation
 * into the cache therefore has to be followed by an explicit refresh.
 * <p>
 * The refresh is coalesced: every finished request only sets a flag, and the
 * actual work happens once per client tick, at most {@value #MIN_INTERVAL_MS} ms
 * apart (10 refreshes per second). A burst of twenty translations in one batch
 * therefore costs exactly one refresh.
 */
public final class RefreshCoordinator {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	/** Upper bound for the refresh rate: one refresh every 100 ms (10/s). */
	private static final long MIN_INTERVAL_MS = 100L;

	private static final AtomicBoolean PENDING = new AtomicBoolean(false);
	/**
	 * Set when everything has to be rebuilt even though no new translation arrived -
	 * the master switch was flipped, the config was saved or the player pressed the
	 * refresh key. Chat lines, advancement widgets and dialog layouts bake their text
	 * at build time, so a generation bump alone does not restore them.
	 */
	private static final AtomicBoolean FORCE_ALL = new AtomicBoolean(false);
	/** A chat request completed and the baked line list must be rebuilt. */
	private static final AtomicBoolean CHAT_TRANSLATION_READY = new AtomicBoolean(false);
	/** Throttle for rebuilding the advancement screen (it recreates every widget). */
	private static final long ADVANCEMENT_REBUILD_MS = 1500L;
	/** Throttle for rebuilding a dialog screen whose title layout must follow the text. */
	private static final long DIALOG_REBUILD_MS = 1500L;
	private static volatile long lastAdvancementRebuild;
	private static volatile long lastDialogRebuild;
	private static volatile long lastRefreshAt;
	private static final AtomicLong REFRESH_COUNT = new AtomicLong();
	private static final AtomicLong SKIPPED_COUNT = new AtomicLong();

	private RefreshCoordinator() {
	}

	/**
	 * Asks for a refresh. Thread safe and cheap: it only sets a flag, so it can be
	 * called from the translation worker threads.
	 */
	public static void request() {
		PENDING.set(true);
	}

	/**
	 * Asks for a rebuild of <em>everything</em> whose text was baked at build time
	 * (chat lines, advancement widgets, dialog layout), not only of the caches that
	 * follow the generation counter.
	 * <p>
	 * Needed whenever the visible result changes without a translation arriving: the
	 * master switch (ninth feedback round: chat kept showing translated lines after
	 * the switch was turned off, because the chat cache was only rebuilt when a chat
	 * translation arrived), a config save, and the manual refresh key.
	 */
	public static void requestFullRefresh() {
		FORCE_ALL.set(true);
		request();
	}

	/**
	 * Called by the completion callback attached to every chat request. Keeping this
	 * explicit avoids relying solely on a batch-level changed-type snapshot: the
	 * player's own message and /teammsg output rebuild as soon as their answer lands.
	 */
	public static void requestChatTranslationRefresh() {
		CHAT_TRANSLATION_READY.set(true);
		if (AITranslateModClient.config != null && AITranslateModClient.config.debugLog) {
			LOGGER.info("[AT] Chat translation ready; rebuilding chat lines");
		}
		request();
	}

	/**
	 * Called every client tick. Runs at most one coalesced refresh; when the rate
	 * limit is reached the flag stays set and the refresh happens on a later tick
	 * instead of being dropped.
	 */
	public static void tick(Minecraft client) {
		if (client == null || !PENDING.compareAndSet(true, false)) {
			return;
		}
		long now = System.currentTimeMillis();
		long since = now - lastRefreshAt;
		if (since < MIN_INTERVAL_MS) {
			SKIPPED_COUNT.incrementAndGet();
			PENDING.set(true);
			return;
		}
		lastRefreshAt = now;
		REFRESH_COUNT.incrementAndGet();
		try {
			refresh(client);
		} catch (RuntimeException e) {
			LOGGER.warn("Refresh failed", e);
		}
	}

	/**
	 * The actual refresh. Everything here is render-layer only:
	 * <ul>
	 * <li>the generation bump invalidates every cache built by this mod (sign
	 * lines, text display lines, book page, dialog labels, render memo);</li>
	 * <li>the chat line cache is rebuilt from the untouched message list, so new
	 * translations appear in place without scrolling the chat to the bottom;</li>
	 * <li>the open screen is asked to refresh its own text caches if it has any
	 * (book page, container title, dialog body).</li>
	 * </ul>
	 */
	public static void refresh(Minecraft client) {
		TranslationSupport.bumpGeneration();
		// A forced refresh pretends that every text type changed, so the baked caches
		// below are rebuilt even though no translation finished in this tick.
		boolean forceAll = FORCE_ALL.getAndSet(false);
		boolean chatTranslationReady = CHAT_TRANSLATION_READY.getAndSet(false);
		Set<TextType> changed = forceAll
				? EnumSet.allOf(TextType.class)
				: (AITranslateModClient.scheduler == null
						? Set.of()
						: AITranslateModClient.scheduler.drainChangedTypes());
		// The chat line cache is the most expensive one (every visible message is
		// re-split with Font.split), so it is only rebuilt when a chat translation
		// actually arrived - or when the master switch / config changed, where the
		// already built lines have to go back to the original text.
		if (chatTranslationReady || changed.contains(TextType.CHAT)) {
			if (client.gui != null && client.gui.hud.getChat() instanceof ChatComponentAccessor accessor) {
				// refreshTrimmedMessages() rebuilds through addMessageToDisplayQueue().
				// Vanilla increments chatScrollbarPos once for every rebuilt line while
				// the chat is focused and scrolled. Snapshot both scroll fields and put
				// them back afterwards so a completed translation cannot move the reader.
				int scrollPosition = accessor.aiTranslate$getChatScrollbarPos();
				boolean unread = accessor.aiTranslate$getNewMessageSinceScroll();
				accessor.aiTranslate$refreshTrimmedMessages();
				if (scrollPosition > 0) {
					int maximum = Math.max(0,
							accessor.aiTranslate$trimmedMessages().size() - accessor.aiTranslate$getLinesPerPage());
					accessor.aiTranslate$setChatScrollbarPos(Math.min(scrollPosition, maximum));
					accessor.aiTranslate$setNewMessageSinceScroll(unread);
				}
			}
		}
		// Screens whose widgets bake their text (and the layout position derived from
		// it) at construction time need a real rebuild to show a translation that
		// arrived after the screen was opened: the advancement screen splits every
		// title once, and a dialog's title is a StringWidget whose position the header
		// layout computed from the original width.
		if (client.gui.screen() instanceof AdvancementsScreen screen) {
			long now = System.currentTimeMillis();
			if (changed.contains(TextType.ADVANCEMENT)
					&& (forceAll || now - lastAdvancementRebuild > ADVANCEMENT_REBUILD_MS)) {
				lastAdvancementRebuild = now;
				try {
					((ScreenAccessor) screen).aiTranslate$rebuildWidgets();
				} catch (RuntimeException e) {
					LOGGER.warn("Could not rebuild the advancement screen", e);
				}
			}
		}
		if (client.gui.screen() instanceof net.minecraft.client.gui.screens.dialog.DialogScreen<?> dialog) {
			long now = System.currentTimeMillis();
			if (changed.contains(TextType.DIALOG) && (forceAll || now - lastDialogRebuild > DIALOG_REBUILD_MS)) {
				lastDialogRebuild = now;
				try {
					((ScreenAccessor) dialog).aiTranslate$rebuildWidgets();
				} catch (RuntimeException e) {
					LOGGER.warn("Could not rebuild the dialog screen", e);
				}
			}
		}
	}

	/**
	 * Runs a full refresh right now on the calling (client) thread, bypassing the tick
	 * coalescing and the {@value #MIN_INTERVAL_MS} ms rate limit.
	 * <p>
	 * Used by the master switch and the manual refresh key: both are single, deliberate
	 * player actions where a delayed rebuild would be visible as a flicker (the chat
	 * would show the translated lines for another tenth of a second).
	 */
	public static void refreshNow(Minecraft client) {
		if (client == null) {
			return;
		}
		FORCE_ALL.set(true);
		// The forced refresh below replaces the queued one, so drop the pending flag to
		// avoid doing the same work twice on the next tick.
		PENDING.set(false);
		lastRefreshAt = System.currentTimeMillis();
		REFRESH_COUNT.incrementAndGet();
		try {
			refresh(client);
		} catch (RuntimeException e) {
			LOGGER.warn("Forced refresh failed", e);
		}
	}

	/** Clears what can be rebuilt from the API and returns how many entries were dropped. */
	public static int clearMemoryCaches() {
		int dropped = 0;
		if (AITranslateModClient.scheduler != null) {
			dropped += AITranslateModClient.scheduler.clearMemoryCache();
		}
		TranslationSupport.clearRenderMemo();
		return dropped;
	}

	public static long refreshCount() {
		return REFRESH_COUNT.get();
	}

	public static long skippedCount() {
		return SKIPPED_COUNT.get();
	}

	/** Human readable state for {@code /aitranslate perf}. */
	public static String summary() {
		return "刷新 " + REFRESH_COUNT.get() + " 次（合并跳过 " + SKIPPED_COUNT.get() + " 次）";
	}

	/**
	 * Shows the "refreshed" feedback on the action bar. Used by the manual refresh
	 * key, which has to be visible even when nothing was translated.
	 */
	public static void toast(Minecraft client, String message) {
		if (client != null && client.gui != null) {
			ModConfig config = AITranslateModClient.config;
			if (config == null || config.showTranslationToast) {
				client.gui.hud.setOverlayMessage(Component.literal(message), false);
			}
		}
	}
}
