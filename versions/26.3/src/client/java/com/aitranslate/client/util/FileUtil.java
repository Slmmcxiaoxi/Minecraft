package com.aitranslate.client.util;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** File system helpers: path sanitising and small JSON helpers. */
public final class FileUtil {
	public static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private static final char[] ILLEGAL = {'\\', '/', ':', '*', '?', '"', '<', '>', '|', '\n', '\r', '\t'};

	private FileUtil() {
	}

	/**
	 * Replaces every character that is illegal in a Windows/Linux file name
	 * with an underscore, so that world names and server addresses can be used
	 * as directory names.
	 */
	public static String sanitize(String raw) {
		if (raw == null || raw.isBlank()) {
			return "unknown";
		}
		StringBuilder sb = new StringBuilder(raw.length());
		for (char c : raw.toCharArray()) {
			boolean illegal = false;
			for (char bad : ILLEGAL) {
				if (c == bad) {
					illegal = true;
					break;
				}
			}
			if (illegal || c < 0x20) {
				sb.append('_');
			} else {
				sb.append(c);
			}
		}
		String result = sb.toString().trim();
		// Windows refuses names ending with a dot or a space.
		while (result.endsWith(".") || result.endsWith(" ")) {
			result = result.substring(0, result.length() - 1);
		}
		if (result.isEmpty()) {
			return "unknown";
		}
		return result;
	}

	public static void writeJson(Path path, JsonElement json) throws IOException {
		Files.createDirectories(path.getParent());
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(json, writer);
		}
	}

	public static JsonElement readJson(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return JsonParser.parseReader(reader);
		}
	}

	public static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		}
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	public static long directorySize(Path dir) {
		if (dir == null || !Files.isDirectory(dir)) {
			return 0L;
		}
		try (var stream = Files.walk(dir)) {
			return stream.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		} catch (IOException e) {
			return 0L;
		}
	}
}
