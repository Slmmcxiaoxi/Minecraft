package com.aitranslate.client.scheduler;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.util.PlaceholderUtil;

/**
 * One text waiting for a translation.
 * <p>
 * Supersedes {@code PendingTranslation} (tenth feedback round): the queue entry
 * now also carries the {@link TranslationPriority} it was requested with, the
 * insertion sequence that keeps the queue in order, and - for the segments of a
 * long text - the hash of the text they belong to.
 * <p>
 * Eleventh feedback round: {@code label} carries where the text came from when the
 * caller knows more than the text type does ("book page 19/21"). It is only used
 * for logging, so a page that fails can be identified in the log by its page
 * number instead of by a hash.
 */
public record QueuedTranslation(TextType textType, String text, String hash, TranslationPriority priority,
		long sequence, String segmentOwner, String label) {

	public QueuedTranslation(TextType textType, String text, String hash, TranslationPriority priority,
			long sequence) {
		this(textType, text, hash, priority, sequence, null, null);
	}

	public QueuedTranslation(TextType textType, String text, String hash, TranslationPriority priority,
			long sequence, String segmentOwner) {
		this(textType, text, hash, priority, sequence, segmentOwner, null);
	}

	/** True when this entry is one segment of a long text. */
	public boolean isSegment() {
		return segmentOwner != null;
	}

	/** The same text with a different urgency (focus moved, watchdog, deferral). */
	public QueuedTranslation withPriority(TranslationPriority other) {
		return new QueuedTranslation(textType, text, hash, other == null ? priority : other, sequence, segmentOwner,
				label);
	}

	/** How this entry is named in the log: the label when the caller gave one. */
	public String describe() {
		return label == null || label.isBlank() ? textType.name().toLowerCase(java.util.Locale.ROOT) : label;
	}

	/** Placeholders that have to survive the round trip. */
	public java.util.List<String> placeholders() {
		return PlaceholderUtil.extract(text);
	}
}
