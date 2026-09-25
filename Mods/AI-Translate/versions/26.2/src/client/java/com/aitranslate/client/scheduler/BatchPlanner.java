package com.aitranslate.client.scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which queued texts make up the next request (tenth feedback round).
 * <p>
 * Two things used to go wrong here. The batch was capped by <em>element count</em>
 * only, so twenty 1000 character book paragraphs could be sent as one ~20 000
 * character request - which is what made a long book (or a dense sign area) time
 * out, and a timed-out request used to switch the whole mod off. And every
 * queued text was treated the same, so background work could delay the page the
 * player was actually reading.
 * <p>
 * This class is the pure decision: given the queue in insertion order, the
 * priority of each entry and the two budgets, it returns the selection. No
 * network, no clock, no Minecraft - which is exactly why it can be unit tested.
 */
public final class BatchPlanner {
	/** One queue entry as the planner sees it. */
	public record Candidate(String key, TranslationPriority priority, int length) {
	}

	/** The selection, plus whether less urgent texts had to wait. */
	public record Plan(List<String> keys, boolean leftBehind) {
		public boolean isEmpty() {
			return keys.isEmpty();
		}
	}

	private final int maxItems;
	private final int maxChars;

	public BatchPlanner(int maxItems, int maxChars) {
		this.maxItems = Math.max(1, maxItems);
		this.maxChars = Math.max(1, maxChars);
	}

	/**
	 * Selects the next batch.
	 * <p>
	 * Rules, in order:
	 * <ol>
	 * <li>higher priority first, insertion order inside one priority;</li>
	 * <li>stop adding once the item budget or the character budget is used up -
	 * but always send at least one text, even when it alone exceeds the character
	 * budget (the alternative would be to never translate it, since a segment
	 * that long is already split before it is queued);</li>
	 * <li>{@link TranslationPriority#LOW} is only ever sent on its own: while
	 * anything more urgent is queued, the background texts wait. That is what
	 * keeps the player's current focus ahead of stale work.</li>
	 * </ol>
	 * {@code leftBehind} is {@code true} when entries were skipped because of a
	 * budget or because they were background work - the caller uses it to plan an
	 * immediate follow-up request instead of waiting for new input.
	 */
	public Plan plan(List<Candidate> candidates) {
		List<String> keys = new ArrayList<>();
		if (candidates == null || candidates.isEmpty()) {
			return new Plan(keys, false);
		}
		int chars = 0;
		boolean focusWorkChosen = false;
		for (TranslationPriority priority : TranslationPriority.values()) {
			for (Candidate candidate : candidates) {
				if (candidate.priority() != priority) {
					continue;
				}
				if (priority == TranslationPriority.LOW && focusWorkChosen) {
					// Background work never joins a request that carries focus work.
					return new Plan(keys, true);
				}
				if (keys.size() >= maxItems) {
					return new Plan(keys, true);
				}
				if (!keys.isEmpty() && chars + candidate.length() > maxChars) {
					// Everything after this entry is later in the queue: leave it for
					// the next request instead of skipping over it.
					return new Plan(keys, true);
				}
				keys.add(candidate.key());
				chars += candidate.length();
				focusWorkChosen |= priority != TranslationPriority.LOW;
			}
		}
		return new Plan(keys, false);
	}
}
