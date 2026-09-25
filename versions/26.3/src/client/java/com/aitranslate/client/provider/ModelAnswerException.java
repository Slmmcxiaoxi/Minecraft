package com.aitranslate.client.provider;

/**
 * The model answered, but not with a usable JSON array (wrong number of
 * elements, prose instead of an array, malformed JSON).
 * <p>
 * Kept as a subclass of {@link IllegalStateException} so the parsing contract of
 * {@link OpenAIProvider} stays what the existing tests describe, while the
 * scheduler can tell this apart from a transport failure: the API clearly works,
 * so it must not be counted towards "the API is unreachable" (tenth feedback
 * round). The recovery is different too - the text is re-sent on its own instead
 * of closing the master switch.
 */
public class ModelAnswerException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	public ModelAnswerException(String message) {
		super(message);
	}

	public ModelAnswerException(String message, Throwable cause) {
		super(message, cause);
	}
}
