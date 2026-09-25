package com.aitranslate.client.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class DictionaryInputsTest {
	@Test
	void splitsTrimsFiltersAndDeduplicates() {
		assertEquals(List.of("Hello", "Byebye", "Yellow"),
				DictionaryInputs.splitOriginals(" ; Hello ; Byebye;; Yellow ; Hello;"));
	}

	@Test
	void escapedSemicolonAndBackslashStayInOriginal() {
		assertEquals(List.of("one;two", "C:\\path"),
				DictionaryInputs.splitOriginals("one\\;two; C:\\\\path"));
	}

	@Test
	void blankInputCreatesNothing() {
		assertEquals(List.of(), DictionaryInputs.splitOriginals(" ; ; "));
	}
}
