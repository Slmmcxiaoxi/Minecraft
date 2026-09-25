package com.aitranslate.client.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FileUtilTest {

	@Test
	void replacesIllegalCharacters() {
		assertEquals("a_b_c_d_e_f_g_h_i", FileUtil.sanitize("a\\b/c:d*e?f\"g<h>i"));
	}

	@Test
	void keepsUnicodeWorldNames() {
		assertEquals("为时已晚", FileUtil.sanitize("为时已晚"));
	}

	@Test
	void fallsBackForEmptyNames() {
		assertEquals("unknown", FileUtil.sanitize("   "));
		assertEquals("unknown", FileUtil.sanitize(null));
	}

	@Test
	void trimsTrailingDotsAndSpaces() {
		assertEquals("world", FileUtil.sanitize("world. "));
	}

	@Test
	void formatsHumanReadableSizes() {
		assertEquals("512 B", FileUtil.humanSize(512));
		assertTrue(FileUtil.humanSize(2048).endsWith("KB"));
		assertTrue(FileUtil.humanSize(5L * 1024 * 1024).endsWith("MB"));
	}

	@Test
	void writesAndReadsJson() throws Exception {
		java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("aitranslate-test");
		java.nio.file.Path file = temp.resolve("sub").resolve("data.json");
		var json = new com.google.gson.JsonObject();
		json.addProperty("hello", "世界");
		FileUtil.writeJson(file, json);
		assertTrue(java.nio.file.Files.isRegularFile(file));
		assertEquals("世界", FileUtil.readJson(file).getAsJsonObject().get("hello").getAsString());
		assertFalse(FileUtil.directorySize(temp) == 0L);
	}
}
