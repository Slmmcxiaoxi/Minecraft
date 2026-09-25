package com.aitranslate.client.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Failure handling (tenth feedback round).
 * <p>
 * The reported bug: a long page made one request time out, one failed request was
 * enough to switch translation off, and a switched-off mod translates nothing -
 * so long text could never be translated. The rules under test here are what
 * breaks that circle: only consecutive <em>transport</em> failures count, a
 * success clears the counter, and a mis-shaped model answer is not an outage.
 */
class FailurePolicyTest {

	@Test
	void oneFailureIsNotEnoughToWarn() {
		FailurePolicy policy = new FailurePolicy();
		assertEquals(1, policy.recordTransportFailure());
		assertFalse(policy.shouldNotify(5), "a single timeout must never close the master switch");
	}

	@Test
	void warningHappensAtTheThreshold() {
		FailurePolicy policy = new FailurePolicy();
		for (int i = 1; i < 5; i++) {
			policy.recordTransportFailure();
			assertFalse(policy.shouldNotify(5), "still below the threshold after " + i + " failure(s)");
		}
		policy.recordTransportFailure();
		assertTrue(policy.shouldNotify(5), "the fifth consecutive failure is a fact, not a hiccup");
	}

	@Test
	void successResetsTheCounter() {
		FailurePolicy policy = new FailurePolicy();
		policy.recordTransportFailure();
		policy.recordTransportFailure();
		policy.recordSuccess();
		assertEquals(0, policy.consecutiveFailures());
		policy.recordTransportFailure();
		assertFalse(policy.shouldNotify(5), "alternating failures and successes never reach the threshold");
	}

	@Test
	void modelAnswerFailuresDoNotCountAsAnOutage() {
		FailurePolicy policy = new FailurePolicy();
		for (int i = 0; i < 20; i++) {
			policy.recordAnswerFailure();
		}
		assertEquals(0, policy.consecutiveFailures(), "the API answered - it is not down");
		assertFalse(policy.shouldNotify(5));
	}

	@Test
	void totalFailuresAreTrackedSeparatelyFromTheStreak() {
		FailurePolicy policy = new FailurePolicy();
		policy.recordTransportFailure();
		policy.recordSuccess();
		policy.recordTransportFailure();
		assertEquals(1, policy.consecutiveFailures());
		assertEquals(2, policy.totalFailures());
	}

	@Test
	void thresholdOfOneIsAllowed() {
		FailurePolicy policy = new FailurePolicy();
		policy.recordTransportFailure();
		assertTrue(policy.shouldNotify(1));
	}

	@Test
	void thresholdIsClampedToAtLeastOne() {
		FailurePolicy policy = new FailurePolicy();
		policy.recordTransportFailure();
		assertTrue(policy.shouldNotify(0), "a zero threshold must not mean 'never warn'");
	}
}
