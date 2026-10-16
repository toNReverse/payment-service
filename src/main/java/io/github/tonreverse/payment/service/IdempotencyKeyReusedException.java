package io.github.tonreverse.payment.service;

import java.io.Serial;

/** The client sent an Idempotency-Key it already used for a different request. The API answers 422. */
public final class IdempotencyKeyReusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used for a different request");
    }
}
