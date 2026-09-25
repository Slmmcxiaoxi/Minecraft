package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Button placement of the in-screen switch (fifteenth feedback round).
 * <p>
 * The feedback document asks for a position that is the same on every screen, that
 * can be dragged to any place, and that survives a resolution change. This test
 * pins the arithmetic that makes those three things consistent: the corner bases,
 * the offsets from a corner, the clamping that keeps a button reachable, and the
 * "snap to the nearest corner" rule the drag screen uses on release.
 */
class ButtonPlacementTest {

	private static final int WIDTH = 28;
	private static final int HEIGHT = 18;

	@Test
	void defaultIsTopRightWithTheConfiguredOffsets() {
		// The shipped default: top right, 10 px in from the right edge, 10 px down.
		ButtonPlacement.Position position = ButtonPlacement.place(ButtonPlacement.Anchor.TOP_RIGHT, -10, 10,
				854, 480, WIDTH, HEIGHT);
		assertEquals(854 - WIDTH - 10, position.x());
		assertEquals(10, position.y());
	}

	@Test
	void everyAnchorHasItsOwnCorner() {
		assertEquals(new ButtonPlacement.Position(0, 0),
				ButtonPlacement.base(ButtonPlacement.Anchor.TOP_LEFT, 320, 240, WIDTH, HEIGHT));
		assertEquals(new ButtonPlacement.Position(320 - WIDTH, 0),
				ButtonPlacement.base(ButtonPlacement.Anchor.TOP_RIGHT, 320, 240, WIDTH, HEIGHT));
		assertEquals(new ButtonPlacement.Position(0, 240 - HEIGHT),
				ButtonPlacement.base(ButtonPlacement.Anchor.BOTTOM_LEFT, 320, 240, WIDTH, HEIGHT));
		assertEquals(new ButtonPlacement.Position(320 - WIDTH, 240 - HEIGHT),
				ButtonPlacement.base(ButtonPlacement.Anchor.BOTTOM_RIGHT, 320, 240, WIDTH, HEIGHT));
		assertEquals(new ButtonPlacement.Position((320 - WIDTH) / 2, (240 - HEIGHT) / 2),
				ButtonPlacement.base(ButtonPlacement.Anchor.CENTER, 320, 240, WIDTH, HEIGHT));
	}

	@Test
	void offsetFollowsTheWindowEdge() {
		// Same anchor and offsets, two window sizes: the distance to the right edge is
		// the same in both, which is exactly what an absolute coordinate cannot do.
		ButtonPlacement.Position small = ButtonPlacement.place(ButtonPlacement.Anchor.TOP_RIGHT, -10, 10,
				640, 360, WIDTH, HEIGHT);
		ButtonPlacement.Position large = ButtonPlacement.place(ButtonPlacement.Anchor.TOP_RIGHT, -10, 10,
				1920, 1080, WIDTH, HEIGHT);
		assertEquals(10, 640 - (small.x() + WIDTH));
		assertEquals(10, 1920 - (large.x() + WIDTH));
	}

	@Test
	void positionIsClampedIntoTheScreen() {
		// An offset that would push the button off screen (a hand-edited file, or a very
		// small window) lands against the edge instead of disappearing.
		ButtonPlacement.Position position = ButtonPlacement.place(ButtonPlacement.Anchor.TOP_LEFT, -500, -500,
				320, 240, WIDTH, HEIGHT);
		assertEquals(new ButtonPlacement.Position(0, 0), position);

		ButtonPlacement.Position other = ButtonPlacement.place(ButtonPlacement.Anchor.TOP_RIGHT, 500, 500,
				320, 240, WIDTH, HEIGHT);
		assertEquals(new ButtonPlacement.Position(320 - WIDTH, 240 - HEIGHT), other);
	}

	@Test
	void nearestPicksTheClosestCorner() {
		// Top left area -> top left; bottom right area -> bottom right; a position in the
		// middle is closest to the centre anchor.
		assertEquals(ButtonPlacement.Anchor.TOP_LEFT,
				ButtonPlacement.nearest(2, 2, 854, 480, WIDTH, HEIGHT));
		assertEquals(ButtonPlacement.Anchor.BOTTOM_RIGHT,
				ButtonPlacement.nearest(854 - WIDTH - 2, 480 - HEIGHT - 2, 854, 480, WIDTH, HEIGHT));
		assertEquals(ButtonPlacement.Anchor.TOP_RIGHT,
				ButtonPlacement.nearest(854 - WIDTH, 0, 854, 480, WIDTH, HEIGHT));
		assertEquals(ButtonPlacement.Anchor.BOTTOM_LEFT,
				ButtonPlacement.nearest(0, 480 - HEIGHT, 854, 480, WIDTH, HEIGHT));
	}

	@Test
	void snappingRoundTrips() {
		// A dragged position is stored as "nearest corner + offset"; placing that pair
		// again has to reproduce the dragged position exactly (as long as it is inside
		// the screen, where nothing is clamped).
		int x = 500;
		int y = 300;
		ButtonPlacement.Anchor anchor = ButtonPlacement.nearest(x, y, 854, 480, WIDTH, HEIGHT);
		ButtonPlacement.Position offsets = ButtonPlacement.offsets(anchor, x, y, 854, 480, WIDTH, HEIGHT);
		ButtonPlacement.Position restored = ButtonPlacement.place(anchor, offsets.x(), offsets.y(), 854, 480,
				WIDTH, HEIGHT);
		assertEquals(new ButtonPlacement.Position(x, y), restored);
	}

	@Test
	void anchorNamesRoundTripThroughTheConfigFile() {
		for (ButtonPlacement.Anchor anchor : ButtonPlacement.Anchor.values()) {
			assertEquals(anchor, ButtonPlacement.Anchor.parse(anchor.id()));
			assertEquals(anchor, ButtonPlacement.Anchor.parse(anchor.id().toLowerCase(java.util.Locale.ROOT)));
			assertEquals(anchor, ButtonPlacement.Anchor.parse("  " + anchor.id() + "  "));
		}
		assertNull(ButtonPlacement.Anchor.parse(null));
		assertNull(ButtonPlacement.Anchor.parse(""));
		assertNull(ButtonPlacement.Anchor.parse("SOMEWHERE"));
		// The fallback used by the config and the tick loop.
		assertEquals(ButtonPlacement.Anchor.TOP_RIGHT,
				ButtonPlacement.Anchor.parseOr("SOMEWHERE", ButtonPlacement.Anchor.TOP_RIGHT));
	}

	@Test
	void descriptionShowsSignsAndTheAnchorName() {
		assertEquals("右上 (-10, +10)", ButtonPlacement.describe(ButtonPlacement.Anchor.TOP_RIGHT, -10, 10));
		assertEquals("居中 (+3, -4)", ButtonPlacement.describe(ButtonPlacement.Anchor.CENTER, 3, -4));
	}
}
