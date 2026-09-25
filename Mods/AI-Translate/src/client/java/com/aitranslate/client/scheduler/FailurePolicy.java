package com.aitranslate.client.scheduler;

/**
 * Decides when "the API is unreachable" is a fact rather than a hiccup (tenth
 * feedback round).
 * <p>
 * Before this class, a single failed request - after the provider's own retries -
 * was enough to switch translation off. Opening one long book page was enough to
 * trigger it, and because a switched-off mod translates nothing, the page stayed
 * untranslated, which is the vicious circle the tenth feedback round reported:
 * the more text, the more likely a timeout, the more likely the switch closing,
 * the less likely long text ever gets translated.
 * <p>
 * The rule now: only <em>consecutive</em> transport failures count, a success
 * resets the counter, and a mis-shaped model answer is not a transport failure at
 * all (that one is handled by re-sending the text on its own). A failure below
 * the threshold changes nothing visible - the text keeps its original and the
 * rest of the queue keeps going.
 */
public final class FailurePolicy {
	/** Consecutive transport failures so far. */
	private int consecutive;
	/** Total transport failures since start-up (for {@code /aitranslate perf}). */
	private long total;

	/**
	 * One request failed in a way that says something about the API
	 * (timeout, refused connection, HTTP error).
	 *
	 * @return the new number of consecutive failures
	 */
	public synchronized int recordTransportFailure() {
		consecutive++;
		total++;
		return consecutive;
	}

	/**
	 * One request failed because the model answered something unusable. The API
	 * itself works, so this must not count towards "the API is down".
	 */
	public synchronized void recordAnswerFailure() {
		// Intentionally does not touch the counter: a model that merges two
		// elements is a model quirk, not an outage.
	}

	/** A request succeeded: the API is clearly back. */
	public synchronized void recordSuccess() {
		consecutive = 0;
	}

	public synchronized int consecutiveFailures() {
		return consecutive;
	}

	public synchronized long totalFailures() {
		return total;
	}

	/**
	 * True when the player has to be told (and the master switch may be turned
	 * off). {@code threshold} is {@code ≥ 1}; the default is five consecutive
	 * failures, so a single slow request never closes the mod.
	 */
	public synchronized boolean shouldNotify(int threshold) {
		return consecutive >= Math.max(1, threshold);
	}
}
