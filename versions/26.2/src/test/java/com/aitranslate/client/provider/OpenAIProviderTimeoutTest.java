package com.aitranslate.client.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.aitranslate.client.config.ModConfig;

/**
 * Timeout and error classification (tenth feedback round).
 * <p>
 * The reported failure mode: opening a long book page produced one big request
 * that outlived a fixed 30 s timeout, the timeout was reported as "the API is
 * down", and the master switch was closed - so the page could never be
 * translated. Two things have to hold: the timeout scales with the payload, and
 * a timeout is not the same thing as an unreachable server.
 */
class OpenAIProviderTimeoutTest {

	private static OpenAIProvider provider(int baseTimeout, int longTimeout) {
		ModConfig config = new ModConfig();
		config.apiKey = "test";
		config.requestTimeoutSeconds = baseTimeout;
		config.longRequestTimeoutSeconds = longTimeout;
		return new OpenAIProvider(config);
	}

	@Test
	void shortRequestUsesTheConfiguredTimeout() {
		OpenAIProvider provider = provider(30, 120);
		assertEquals(30, provider.timeoutSecondsFor(20, 0));
	}

	@Test
	void longRequestGetsMoreTime() {
		OpenAIProvider provider = provider(30, 120);
		int shortTimeout = provider.timeoutSecondsFor(100, 0);
		int longTimeout = provider.timeoutSecondsFor(800, 0);
		assertTrue(longTimeout > shortTimeout,
				"an 800 character batch needs more time than a 100 character one (" + longTimeout + " vs "
						+ shortTimeout + ")");
	}

	@Test
	void timeoutIsCappedByTheLongRequestCeiling() {
		OpenAIProvider provider = provider(30, 120);
		assertEquals(120, provider.timeoutSecondsFor(100_000, 0), "the ceiling must hold");
		assertEquals(120, provider.timeoutSecondsFor(5000, 4), "retries must not grow past the ceiling");
	}

	@Test
	void ceilingNeverGoesBelowTheBaseTimeout() {
		OpenAIProvider provider = provider(60, 10);
		assertEquals(60, provider.timeoutSecondsFor(0, 0));
	}

	@Test
	void timeoutIsClassifiedAsStillProcessing() {
		Throwable classified = OpenAIProvider.classify(new CompletionException(
				new HttpTimeoutException("request timed out")));
		TranslationTransportException transport = assertInstanceOf(TranslationTransportException.class, classified);
		assertTrue(transport.timeout(), "a timeout means the server may still be working");
		assertFalse(transport.permanent(), "a timeout is not a configuration error");
	}

	@Test
	void connectionErrorIsClassifiedAsTransport() {
		Throwable classified = OpenAIProvider.classify(new ConnectException("Connection refused"));
		TranslationTransportException transport = assertInstanceOf(TranslationTransportException.class, classified);
		assertFalse(transport.timeout());
		assertFalse(transport.permanent());
	}

	@Test
	void wrongElementCountIsAModelAnswerProblem() {
		Throwable classified = OpenAIProvider.classify(
				new ModelAnswerException("Expected 19 translations but got 12"));
		assertInstanceOf(ModelAnswerException.class, classified);
	}

	@Test
	void nestedCausesAreUnwrapped() {
		Throwable nested = new CompletionException(new java.util.concurrent.ExecutionException(
				new HttpTimeoutException("timed out")));
		assertInstanceOf(TranslationTransportException.class, OpenAIProvider.classify(nested));
	}
}
