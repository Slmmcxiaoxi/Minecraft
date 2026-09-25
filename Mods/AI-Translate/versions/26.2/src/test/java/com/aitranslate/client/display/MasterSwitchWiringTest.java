package com.aitranslate.client.display;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Wiring guard for the master switch.
 * <p>
 * Ninth feedback round: turning the master switch off left the chat showing the
 * translated messages, because {@code RefreshCoordinator} had a
 * {@code requestChatRebuild()} method and a {@code FORCE_CHAT_REBUILD} flag that
 * <em>nothing ever called</em>. Nothing about that defect is visible in a normal
 * unit test - the code compiled, the flag was read, only the setter was missing -
 * so this test asserts the wiring itself instead: the flag is consumed, and every
 * place that can flip the switch asks for a full (forced) refresh.
 * <p>
 * It reads the sources as text, which is crude but exactly the right level: the
 * bug was a missing call, and a missing call cannot be caught by calling the code.
 */
class MasterSwitchWiringTest {
	/** Sources are only reachable when the tests run from the project directory. */
	private static final Path SOURCE_ROOT = Path.of("src", "client", "java", "com", "aitranslate", "client");

	@Test
	void theForceFlagIsConsumedByTheRefresh() throws IOException {
		String source = read("display", "RefreshCoordinator.java");

		assertTrue(source.contains("FORCE_ALL.getAndSet(false)"),
				"the forced-refresh flag must be consumed inside refresh(Minecraft)");
		assertTrue(source.contains("requestFullRefresh()"),
				"the forced refresh must have a public entry point");
		assertFalse(source.contains("FORCE_CHAT_REBUILD"),
				"the old chat-only flag was never called and must not come back");
		assertFalse(source.contains("requestChatRebuild"),
				"the old chat-only entry point must not come back");
	}

	@Test
	void theQueuedRequestStillExistsForTranslations() throws IOException {
		String source = read("display", "RefreshCoordinator.java");

		// The forced refresh is the exception; the normal path must stay coalesced.
		assertTrue(source.contains("public static void request()"),
				"the coalesced request must stay available for arrived translations");
	}

	@Test
	void everySwitchPathForcesAFullRefresh() throws IOException {
		assertTrue(read("keybinding", "AITranslateKeybindings.java")
				.contains("AITranslateModClient.refreshNow()"),
				"the master-switch key must refresh immediately, not only queue a request");
		assertTrue(read("AITranslateModClient.java").contains("requestFullRefresh()"),
				"a saved config must rebuild the baked caches");

		String command = read("command", "AITranslateCommand.java");
		int firstOn = command.indexOf("literal(\"on\")");
		int firstOff = command.indexOf("literal(\"off\")");
		assertTrue(firstOn >= 0 && firstOff > firstOn, "the on/off subcommands must exist");
		String onBranch = command.substring(firstOn, firstOff);
		String offBranch = command.substring(firstOff, Math.min(command.length(), firstOff + 400));
		assertTrue(onBranch.contains("refreshNow()"), "/aitranslate on must refresh immediately");
		assertTrue(offBranch.contains("refreshNow()"), "/aitranslate off must refresh immediately");

		assertTrue(read("util", "ApiFailureNotifier.java").contains("RefreshCoordinator.refreshNow("),
				"the automatic switch-off must refresh too, otherwise the UI keeps the translation");
	}

	@Test
	void theForcedRefreshBypassesTheThrottle() throws IOException {
		String source = read("display", "RefreshCoordinator.java");

		// The advancement / dialog rebuilds are throttled to 1.5 s; a forced refresh
		// has to skip that, otherwise flipping the switch would look laggy.
		assertTrue(source.contains("forceAll || now - lastAdvancementRebuild"),
				"a forced refresh must skip the advancement rebuild throttle");
		assertTrue(source.contains("forceAll || now - lastDialogRebuild"),
				"a forced refresh must skip the dialog rebuild throttle");
	}

	private static String read(String... path) throws IOException {
		Path file = SOURCE_ROOT;
		for (String part : path) {
			file = file.resolve(part);
		}
		assumeTrue(Files.isRegularFile(file), () -> "source not reachable from " + Path.of("").toAbsolutePath());
		return Files.readString(file, StandardCharsets.UTF_8);
	}
}
