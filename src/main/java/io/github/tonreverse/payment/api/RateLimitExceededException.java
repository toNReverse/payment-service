package io.github.tonreverse.payment.api;

import java.io.Serial;

/** The client sent too many requests in the current window. Answered 429 with Retry-After. */
final class RateLimitExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    RateLimitExceededException(long retryAfterSeconds) {
        super("Too many orders from this address; retry in " + retryAfterSeconds + " seconds");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
