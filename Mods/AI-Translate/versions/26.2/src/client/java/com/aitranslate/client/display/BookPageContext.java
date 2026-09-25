package com.aitranslate.client.display;

/**
 * Which page of the open book is being rendered (eleventh feedback round).
 * <p>
 * The page redirect and the page cache invalidation live in two different mixins,
 * and only the second one can see the {@code BookViewScreen} instance and
 * therefore its page number. This tiny holder carries the number from one to the
 * other inside the same render call, which is what lets the mod name a page
 * ("book page 19/21") in the log instead of a hash.
 * <p>
 * Per thread on purpose: the book screen renders on the client render thread, and
 * nothing else may inherit a stale page number.
 */
public final class BookPageContext {
	private static final ThreadLocal<int[]> CURRENT = ThreadLocal.withInitial(() -> new int[] { -1, -1 });

	private BookPageContext() {
	}

	/** Called by the {@code visitText} hook: 0 based page index and the page count. */
	public static void set(int page, int pageCount) {
		int[] value = CURRENT.get();
		value[0] = page;
		value[1] = pageCount;
	}

	/** The 0 based index of the page being rendered, or -1. */
	public static int page() {
		return CURRENT.get()[0];
	}

	/** The number of pages of the open book, or -1. */
	public static int pageCount() {
		return CURRENT.get()[1];
	}

	/**
	 * How this page is named in the log: {@code "book page 19/21"} (1 based, as the
	 * game shows it), or {@code "book page"} when the count is unknown.
	 */
	public static String label() {
		int[] value = CURRENT.get();
		if (value[0] < 0) {
			return "book page";
		}
		if (value[1] <= 0) {
			return "book page " + (value[0] + 1);
		}
		return "book page " + (value[0] + 1) + "/" + value[1];
	}
}
