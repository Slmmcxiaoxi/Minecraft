package com.aitranslate.client.util;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

/** Small replacement for the directory opener removed from Minecraft 26.3. */
public final class PlatformFiles {
	private PlatformFiles() {}

	public static void openDirectory(Path directory) throws IOException {
		if (!Desktop.isDesktopSupported()) {
			throw new IOException("Desktop integration is unavailable");
		}
		Desktop desktop = Desktop.getDesktop();
		if (!desktop.isSupported(Desktop.Action.OPEN)) {
			throw new IOException("Opening directories is unavailable");
		}
		desktop.open(directory.toFile());
	}
}
