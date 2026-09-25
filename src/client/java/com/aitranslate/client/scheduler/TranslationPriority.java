package com.aitranslate.client.scheduler;

/**
 * How urgent a translation is (tenth feedback round).
 * <p>
 * The mod translates what is on screen, and the player's attention is not
 * evenly distributed over it: the book page in front of them matters more than a
 * sign somewhere at the edge of the view, and the text they are looking at
 * through the crosshair matters more than a chat line they may not have read
 * yet. The scheduler therefore keeps one queue per level and drains it in this
 * order:
 * <ul>
 * <li>{@link #HIGH} - the current visual focus: the open screen (book, dialog,
 * container, advancements), the hovered tooltip, the HUD (title, action bar,
 * boss bar, scoreboard) and whatever the crosshair is pointing at;</li>
 * <li>{@link #NORMAL} - content the player may interact with: new chat lines,
 * rendered signs / entity names / text displays, item names and lore;</li>
 * <li>{@link #LOW} - background work: texts whose focus has already moved away,
 * deferred leftovers and anything a future prefetch produces.</li>
 * </ul>
 * While a {@link #HIGH} text is waiting, {@link #LOW} texts are held back so the
 * limited request budget serves the visible text first.
 */
public enum TranslationPriority {
	/** The player is (or just was) looking at this text. */
	HIGH(0),
	/** Visible, but not the current focus. */
	NORMAL(1),
	/** Background / stale: only translated when nothing more important waits. */
	LOW(2);

	private final int rank;

	TranslationPriority(int rank) {
		this.rank = rank;
	}

	/** Lower is more urgent. */
	public int rank() {
		return rank;
	}

	/** The more urgent of the two (used when a text is requested twice). */
	public TranslationPriority moreUrgent(TranslationPriority other) {
		if (other == null) {
			return this;
		}
		return rank <= other.rank ? this : other;
	}
}
