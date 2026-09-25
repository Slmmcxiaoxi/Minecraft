package com.aitranslate.client.focus;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.aitranslate.client.AITranslateModClient;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.scheduler.TranslationPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Where the player is looking right now (tenth feedback round).
 * <p>
 * Rendering already decides <em>what</em> gets translated - this mod only ever
 * translates text the game is drawing - but it says nothing about <em>order</em>.
 * A crosshair that points at a sign and a sign somewhere at the edge of the view
 * are both "being rendered", yet only one of them is what the player is reading.
 * <p>
 * The tracker answers that with the game's own hit result:
 * <ul>
 * <li>{@code Minecraft#crosshairPickEntity} - the entity under the crosshair
 * (its name, and for a text display its text);</li>
 * <li>{@code Minecraft#hitResult} - the block under the crosshair (for a sign,
 * all four lines of the side being looked at);</li>
 * <li>{@code Minecraft#screen} - an open screen is by definition the focus, so the
 * sources that only render inside a screen (book, dialog, container, advancement)
 * and the hovered tooltip are HIGH;</li>
 * <li>the HUD (title, action bar, boss bar, scoreboard) is drawn over whatever
 * the player looks at, so it is HIGH as well;</li>
 * <li>chat and item names are visible but not necessarily being read: NORMAL;</li>
 * <li>anything requested while a different focus was active is background: LOW.</li>
 * </ul>
 * Everything here is read only and once per client tick; nothing is written back
 * into the world.
 */
public final class FocusTracker {
	/** Texts under the crosshair (sign lines, entity name, text display text). */
	private static final Set<String> CROSSHAIR_TEXTS = ConcurrentHashMap.newKeySet();
	/** Bumped whenever the focus changes, so queued work can be re-evaluated. */
	private static final AtomicLong GENERATION = new AtomicLong(1L);
	private static volatile boolean screenOpen;
	private static volatile String crosshairLabel = "";
	private static volatile int lastRangeCellX = Integer.MIN_VALUE;
	private static volatile int lastRangeCellY = Integer.MIN_VALUE;
	private static volatile int lastRangeCellZ = Integer.MIN_VALUE;

	private FocusTracker() {
	}

	public static long generation() {
		return GENERATION.get();
	}

	public static boolean screenOpen() {
		return screenOpen;
	}

	/** Human readable description of what the crosshair points at (logs / self test). */
	public static String crosshairLabel() {
		return crosshairLabel;
	}

	public static Set<String> crosshairTexts() {
		return Set.copyOf(CROSSHAIR_TEXTS);
	}

	/** Called once per client tick from {@code AITranslateModClient#onClientTick}. */
	public static void tick(Minecraft client) {
		if (client == null) {
			return;
		}
		boolean open = client.screen != null;
		String label = "";
		Set<String> texts = ConcurrentHashMap.newKeySet();
		boolean rangeCellChanged = false;
		try {
			LocalPlayer player = client.player;
			if (player != null && client.level != null) {
				int cellX = (int) Math.floor(player.getX() / 8.0D);
				int cellY = (int) Math.floor(player.getY() / 8.0D);
				int cellZ = (int) Math.floor(player.getZ() / 8.0D);
				rangeCellChanged = cellX != lastRangeCellX || cellY != lastRangeCellY || cellZ != lastRangeCellZ;
				lastRangeCellX = cellX;
				lastRangeCellY = cellY;
				lastRangeCellZ = cellZ;
				Entity entity = client.crosshairPickEntity;
				if (entity != null && entity.isAlive()) {
					Component name = entity.getDisplayName();
					if (name != null && !name.getString().isBlank()) {
						texts.add(name.getString());
					}
					label = "entity:" + name.getString();
					if (entity instanceof Display.TextDisplay textDisplay) {
						Component displayed = textDisplay.textRenderState() == null ? null
								: textDisplay.textRenderState().text();
						if (displayed != null && !displayed.getString().isBlank()) {
							texts.add(displayed.getString());
						}
					}
				}
				HitResult hit = client.hitResult;
				if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
					BlockEntity blockEntity = client.level.getBlockEntity(blockHit.getBlockPos());
					if (blockEntity instanceof SignBlockEntity sign) {
						SignText front = sign.getFrontText();
						SignText back = sign.getBackText();
						int lines = collectSign(front, texts);
						lines += collectSign(back, texts);
						label = lines > 0 ? "sign:" + lines + " line(s)" : label;
					}
				}
			}
		} catch (RuntimeException e) {
			// The focus tracker is a hint, never a reason to break rendering.
			return;
		}
		boolean screenChanged = open != screenOpen;
		boolean crosshairChanged = !texts.equals(CROSSHAIR_TEXTS) || !label.equals(crosshairLabel);
		boolean changed = screenChanged || crosshairChanged;
		CROSSHAIR_TEXTS.clear();
		CROSSHAIR_TEXTS.addAll(texts);
		screenOpen = open;
		crosshairLabel = label;
		if (changed) {
			GENERATION.incrementAndGet();
			if (AITranslateModClient.scheduler != null) {
				AITranslateModClient.scheduler.onFocusChanged(screenChanged, crosshairChanged);
			}
		}
		if (rangeCellChanged && AITranslateModClient.config != null
				&& AITranslateModClient.config.enableRangeDetection) {
			// Sign and text-display render lines are cached. Re-evaluate them after the
			// player moves between eight-block cells so text that just entered the radius
			// can be queued without rebuilding those caches every frame.
			com.aitranslate.client.display.TranslationSupport.bumpGeneration();
		}
	}

	private static int collectSign(SignText signText, Set<String> out) {
		if (signText == null) {
			return 0;
		}
		int count = 0;
		// Both the filtered and the unfiltered variant: the renderer picks one of
		// them depending on whether the server filtered the sign, and the crosshair
		// must match whichever is on screen.
		for (boolean filtered : new boolean[] { true, false }) {
			for (Component line : signText.getMessages(filtered)) {
				String text = line == null ? "" : line.getString();
				if (!text.isBlank()) {
					out.add(text);
					count++;
				}
			}
		}
		return count;
	}

	/** True when this exact text is what the crosshair is pointing at. */
	public static boolean isFocusedText(String text) {
		return text != null && !text.isEmpty() && CROSSHAIR_TEXTS.contains(text);
	}

	/**
	 * Distance at which world text still counts as "the player is looking at it"
	 * (tier 1 of the tenth feedback round: signs and entity names near the player).
	 */
	public static final double NEAR_DISTANCE_BLOCKS = 12.0D;
	/**
	 * Beyond this the text is at the edge of the view: it stays translatable but
	 * only after everything closer has been asked for (tier 3).
	 */
	public static final double FAR_DISTANCE_BLOCKS = 40.0D;

	/**
	 * Priority for world text whose distance to the player the render hook knows
	 * (signs, entity name tags).
	 * <p>
	 * Rendering already decided that the text is visible - that is why the hook is
	 * running - but "visible at the horizon" and "two blocks in front of the player"
	 * deserve very different urgency when the request budget is limited.
	 */
	public static TranslationPriority priorityForDistance(TextType type, double distanceBlocks) {
		if (Double.isNaN(distanceBlocks)) {
			return basePriority(type);
		}
		if (distanceBlocks <= NEAR_DISTANCE_BLOCKS) {
			return TranslationPriority.HIGH;
		}
		if (distanceBlocks <= FAR_DISTANCE_BLOCKS) {
			return basePriority(type);
		}
		return TranslationPriority.LOW;
	}

	/** Whether a world-text hook may queue/render a translation at this distance. */
	public static boolean withinTranslationRange(TextType type, String text, double distanceBlocks) {
		var config = AITranslateModClient.config;
		boolean enabled = config != null && config.enableRangeDetection;
		int radius = config == null ? 64 : config.translationRangeBlocks;
		boolean focused = isFocusedText(text)
				|| type == TextType.SIGN && crosshairLabel.startsWith("sign:");
		return RangePolicy.allows(enabled, radius, distanceBlocks, focused);
	}

	/**
	 * The priority a text of this source gets when it is requested. The crosshair
	 * beats the source: a sign the player looks at is more urgent than a chat line.
	 */
	public static TranslationPriority priorityFor(TextType type, String text) {
		if (isFocusedText(text)) {
			return TranslationPriority.HIGH;
		}
		return basePriority(type);
	}

	/**
	 * The default urgency of a text source.
	 * <p>
	 * A source that is only ever requested while it is on screen (a book page, a
	 * tooltip, a dialog line) is HIGH by nature; the sources that are rendered
	 * around the player (signs, entity names, chat) are NORMAL and only become HIGH
	 * when the crosshair points at them.
	 */
	public static TranslationPriority basePriority(TextType type) {
		return switch (type) {
			// Current visual focus: an open screen, the hovered tooltip, the HUD.
			case BOOK, DIALOG, CONTAINER, ADVANCEMENT, TOOLTIP -> TranslationPriority.HIGH;
			case TITLE, ACTION_BAR, BOSS_BAR, SCOREBOARD -> TranslationPriority.HIGH;
			// Visible, but the player may not be reading it.
			case CHAT, ITEM, SIGN, ENTITY_NAME, TEXT_DISPLAY, PLAYER_LIST -> TranslationPriority.NORMAL;
		};
	}

}
