package com.aitranslate.client.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Batch selection (tenth feedback round).
 * <p>
 * Two budgets and three priority levels. The bug this replaces: the batch was
 * capped by element count only, so twenty long book paragraphs became one
 * ~20 000 character request - too slow to answer before the timeout, and a
 * timeout used to switch the whole mod off.
 */
class BatchPlannerTest {

	private static List<BatchPlanner.Candidate> candidates(Object... priorityAndLength) {
		List<BatchPlanner.Candidate> out = new ArrayList<>();
		for (int i = 0; i < priorityAndLength.length; i += 2) {
			TranslationPriority priority = (TranslationPriority) priorityAndLength[i];
			int length = (Integer) priorityAndLength[i + 1];
			out.add(new BatchPlanner.Candidate("c" + (i / 2), priority, length));
		}
		return out;
	}

	@Test
	void characterBudgetStopsTheBatch() {
		BatchPlanner planner = new BatchPlanner(20, 100);
		BatchPlanner.Plan plan = planner.plan(candidates(
				TranslationPriority.NORMAL, 40,
				TranslationPriority.NORMAL, 40,
				TranslationPriority.NORMAL, 40));
		assertEquals(List.of("c0", "c1"), plan.keys(), "80 + 40 would exceed the 100 character budget");
		assertTrue(plan.leftBehind(), "the leftover must be reported so the next request is planned");
	}

	@Test
	void itemBudgetStopsTheBatch() {
		BatchPlanner planner = new BatchPlanner(2, 1000);
		BatchPlanner.Plan plan = planner.plan(candidates(
				TranslationPriority.NORMAL, 1,
				TranslationPriority.NORMAL, 1,
				TranslationPriority.NORMAL, 1));
		assertEquals(List.of("c0", "c1"), plan.keys());
	}

	@Test
	void oneOversizedTextIsStillSent() {
		// Segmentation normally prevents this, but a single unbreakable token must
		// not be dropped forever just because it exceeds the budget on its own.
		BatchPlanner planner = new BatchPlanner(20, 100);
		BatchPlanner.Plan plan = planner.plan(candidates(TranslationPriority.NORMAL, 5000));
		assertEquals(List.of("c0"), plan.keys());
	}

	@Test
	void highPriorityComesFirst() {
		BatchPlanner planner = new BatchPlanner(2, 1000);
		BatchPlanner.Plan plan = planner.plan(candidates(
				TranslationPriority.LOW, 10,
				TranslationPriority.NORMAL, 10,
				TranslationPriority.HIGH, 10,
				TranslationPriority.HIGH, 10));
		assertEquals(List.of("c2", "c3"), plan.keys(), "the two HIGH entries win the two slots");
	}

	@Test
	void backgroundWorkNeverJoinsAFocusRequest() {
		BatchPlanner planner = new BatchPlanner(20, 1000);
		BatchPlanner.Plan plan = planner.plan(candidates(
				TranslationPriority.HIGH, 10,
				TranslationPriority.LOW, 10));
		assertEquals(List.of("c0"), plan.keys(), "the LOW entry waits for a request of its own");
		assertTrue(plan.leftBehind());
	}

	@Test
	void backgroundWorkIsSentWhenNothingElseWaits() {
		BatchPlanner planner = new BatchPlanner(20, 1000);
		BatchPlanner.Plan plan = planner.plan(candidates(
				TranslationPriority.LOW, 10,
				TranslationPriority.LOW, 10));
		assertEquals(List.of("c0", "c1"), plan.keys());
	}

	@Test
	void remainingHighWorkBlocksLowerLevels() {
		BatchPlanner planner = new BatchPlanner(1, 1000);
		BatchPlanner.Plan plan = planner.plan(candidates(
				TranslationPriority.HIGH, 10,
				TranslationPriority.HIGH, 10,
				TranslationPriority.NORMAL, 10));
		assertEquals(List.of("c0"), plan.keys(), "NORMAL must not jump ahead of a waiting HIGH entry");
		assertTrue(plan.leftBehind());
	}

	@Test
	void orderInsideALevelIsInsertionOrder() {
		BatchPlanner planner = new BatchPlanner(3, 1000);
		BatchPlanner.Plan plan = planner.plan(candidates(
				TranslationPriority.NORMAL, 5,
				TranslationPriority.NORMAL, 5,
				TranslationPriority.NORMAL, 5));
		assertEquals(List.of("c0", "c1", "c2"), plan.keys());
	}

	@Test
	void emptyQueueGivesEmptyPlan() {
		assertTrue(new BatchPlanner(20, 800).plan(List.of()).isEmpty());
	}
}
