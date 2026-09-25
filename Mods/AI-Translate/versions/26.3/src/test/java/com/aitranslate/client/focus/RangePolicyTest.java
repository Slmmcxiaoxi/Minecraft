package com.aitranslate.client.focus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RangePolicyTest {
	@Test
	void rangeCanBeDisabled() {
		assertTrue(RangePolicy.allows(false, 32, 500, false));
	}

	@Test
	void focusedTextAlwaysWins() {
		assertTrue(RangePolicy.allows(true, 32, 500, true));
	}

	@Test
	void nearTextPassesAndFarTextWaits() {
		assertTrue(RangePolicy.allows(true, 32, 31.9, false));
		assertFalse(RangePolicy.allows(true, 32, 32.1, false));
		assertTrue(RangePolicy.allows(true, 32, Double.NaN, false));
	}
}
