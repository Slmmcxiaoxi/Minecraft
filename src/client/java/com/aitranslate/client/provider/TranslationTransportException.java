package com.aitranslate.client.provider;

/**
 * The API could not be reached, answered with an error status, or took too long
 * (tenth feedback round).
 * <p>
 * The distinction this type carries is the difference between "the server is
 * broken / the key is wrong" ({@link #permanent()}) and "this particular request
 * was too big or the server is still busy" (not permanent). Only the permanent
 * kind may close the master switch, and only after several consecutive failures;
 * the temporary kind means the text keeps its original and is retried later with
 * a smaller payload.
 */
public class TranslationTransportException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final boolean permanent;
	private final boolean timeout;

	public TranslationTransportException(String message, boolean permanent, boolean timeout, Throwable cause) {
		super(message, cause);
		this.permanent = permanent;
		this.timeout = timeout;
	}

	/** A wrong key, a missing model or an unknown endpoint will not fix itself. */
	public boolean permanent() {
		return permanent;
	}

	/** The request may still be running on the server side - not a network fault. */
	public boolean timeout() {
		return timeout;
	}
}
