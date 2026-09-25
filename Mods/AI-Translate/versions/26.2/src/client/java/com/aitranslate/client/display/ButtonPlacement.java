package com.aitranslate.client.display;

import java.util.Locale;

/**
 * Where the in-screen "译 / 原" button goes (fifteenth feedback round).
 * <p>
 * The feedback document asks for one position that every screen uses, for the
 * position to be adjustable by dragging, and for the setting to survive a
 * resolution change. Both requirements are met by storing the position as
 * <em>anchor + offset</em> instead of as absolute coordinates:
 *
 * <ul>
 * <li>the anchor names a corner (or the centre) of the screen;</li>
 * <li>the offset is measured from that corner, so a button parked next to the top
 * right corner stays there when the window grows - an absolute coordinate would
 * end up in the middle of a wider screen.</li>
 * </ul>
 *
 * Everything here is pure geometry: no game classes, no config access, so the
 * corner arithmetic, the clamping and the "which corner is this position nearest
 * to" rule used by the drag screen are covered by unit tests.
 */
public final class ButtonPlacement {

	/** The corners (and the centre) a button can be anchored to. */
	public enum Anchor {
		TOP_LEFT("左上"),
		TOP_RIGHT("右上"),
		BOTTOM_LEFT("左下"),
		BOTTOM_RIGHT("右下"),
		CENTER("居中");

		private final String label;

		Anchor(String label) {
			this.label = label;
		}

		/** Chinese name, used by the drag screen's readout. */
		public String label() {
			return label;
		}

		/** The name written to the config file. */
		public String id() {
			return name();
		}

		/**
		 * Reads an anchor from its config name.
		 *
		 * @return the anchor, or {@code null} when the text is unknown/blank - the
		 *         caller decides what the fallback is (the config uses
		 *         {@code TOP_RIGHT}, the drag screen never passes rubbish)
		 */
		public static Anchor parse(String raw) {
			if (raw == null) {
				return null;
			}
			String trimmed = raw.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			for (Anchor anchor : values()) {
				if (anchor.name().equalsIgnoreCase(trimmed)) {
					return anchor;
				}
			}
			return null;
		}

		/** Like {@link #parse} but with the mod's default instead of {@code null}. */
		public static Anchor parseOr(String raw, Anchor fallback) {
			Anchor parsed = parse(raw);
			return parsed == null ? fallback : parsed;
		}
	}

	/** A top-left corner of the button in GUI coordinates. */
	public record Position(int x, int y) {
	}

	private ButtonPlacement() {
	}

	/**
	 * The corner position of an anchor with no offset: exactly where the button
	 * would sit with offsets {@code 0 / 0}.
	 */
	public static Position base(Anchor anchor, int screenWidth, int screenHeight, int width, int height) {
		Anchor effective = anchor == null ? Anchor.TOP_RIGHT : anchor;
		int maxX = Math.max(0, screenWidth - width);
		int maxY = Math.max(0, screenHeight - height);
		return switch (effective) {
			case TOP_LEFT -> new Position(0, 0);
			case TOP_RIGHT -> new Position(maxX, 0);
			case BOTTOM_LEFT -> new Position(0, maxY);
			case BOTTOM_RIGHT -> new Position(maxX, maxY);
			case CENTER -> new Position(maxX / 2, maxY / 2);
		};
	}

	/**
	 * The button position for an anchor and its offsets, kept inside the screen.
	 * <p>
	 * The clamp is what makes a position that no longer fits - the window was
	 * resized, or the offset was typed into the file - readable instead of invisible:
	 * the button ends up against the closest edge rather than off-screen.
	 */
	public static Position place(Anchor anchor, int offsetX, int offsetY, int screenWidth, int screenHeight,
			int width, int height) {
		Position origin = base(anchor, screenWidth, screenHeight, width, height);
		return clamp(origin.x() + offsetX, origin.y() + offsetY, screenWidth, screenHeight, width, height);
	}

	/** Keeps a top-left corner inside the screen. */
	public static Position clamp(int x, int y, int screenWidth, int screenHeight, int width, int height) {
		int maxX = Math.max(0, screenWidth - width);
		int maxY = Math.max(0, screenHeight - height);
		return new Position(Math.max(0, Math.min(maxX, x)), Math.max(0, Math.min(maxY, y)));
	}

	/**
	 * The anchor whose base position is closest to a dragged position.
	 * <p>
	 * The drag screen uses this on release: dragging is free-form, but the stored
	 * value is always "nearest corner + offset", so the result keeps following the
	 * window edges instead of being pinned to the coordinates of one window size.
	 */
	public static Anchor nearest(int x, int y, int screenWidth, int screenHeight, int width, int height) {
		Anchor best = Anchor.TOP_RIGHT;
		long bestDistance = Long.MAX_VALUE;
		for (Anchor anchor : Anchor.values()) {
			Position origin = base(anchor, screenWidth, screenHeight, width, height);
			long dx = (long) x - origin.x();
			long dy = (long) y - origin.y();
			long distance = dx * dx + dy * dy;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = anchor;
			}
		}
		return best;
	}

	/** The offsets that turn an anchor's base position into a dragged position. */
	public static Position offsets(Anchor anchor, int x, int y, int screenWidth, int screenHeight, int width,
			int height) {
		Position origin = base(anchor, screenWidth, screenHeight, width, height);
		return new Position(x - origin.x(), y - origin.y());
	}

	/** Readout text for the drag screen, e.g. {@code 右上 (-10, +10)}. */
	public static String describe(Anchor anchor, int offsetX, int offsetY) {
		Anchor effective = anchor == null ? Anchor.TOP_RIGHT : anchor;
		return effective.label() + " (" + signed(offsetX) + ", " + signed(offsetY) + ")";
	}

	private static String signed(int value) {
		return String.format(Locale.ROOT, "%+d", value);
	}
}
