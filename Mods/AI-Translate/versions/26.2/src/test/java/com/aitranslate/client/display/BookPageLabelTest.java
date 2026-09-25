package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.scheduler.QueuedTranslation;
import com.aitranslate.client.scheduler.TranslationPriority;

/**
 * The naming the eleventh feedback round asked for in the log: a book page has to
 * be identifiable by its page number, both while it is being translated and when
 * it fails ("页面索引、内容摘要、失败原因").
 */
class BookPageLabelTest {

	@Test
	void theLabelIsOneBasedLikeTheGameShowsIt() {
		BookPageContext.set(18, 21);
		assertEquals("book page 19/21", BookPageContext.label());
		assertEquals(18, BookPageContext.page());
		assertEquals(21, BookPageContext.pageCount());
	}

	@Test
	void anUnknownPageCountStillNamesThePage() {
		BookPageContext.set(4, -1);
		assertEquals("book page 5", BookPageContext.label());
	}

	@Test
	void aQueuedPageIsDescribedByItsLabel() {
		QueuedTranslation pending = new QueuedTranslation(TextType.BOOK, "text", "hash",
				TranslationPriority.HIGH, 1L, null, "book page 19/21");
		assertEquals("book page 19/21", pending.describe());
		assertEquals("book page 19/21", pending.withPriority(TranslationPriority.LOW).describe(),
				"a demoted page must stay identifiable");
	}

	@Test
	void anUnlabelledEntryFallsBackToItsTextType() {
		QueuedTranslation pending = new QueuedTranslation(TextType.CHAT, "text", "hash",
				TranslationPriority.NORMAL, 1L);
		assertTrue(pending.describe().contains("chat"), pending.describe());
	}
}
