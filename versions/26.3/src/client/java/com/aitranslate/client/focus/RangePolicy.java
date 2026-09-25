package com.aitranslate.client.focus;

/** Pure distance decision used by world-text render hooks. */
public final class RangePolicy {
	private RangePolicy() {
	}

	public static boolean allows(boolean enabled, int radiusBlocks, double distanceBlocks, boolean focused) {
		if (!enabled || focused || Double.isNaN(distanceBlocks)) {
			return true;
		}
		return distanceBlocks <= Math.max(8, radiusBlocks);
	}
}
