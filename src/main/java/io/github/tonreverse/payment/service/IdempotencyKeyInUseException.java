package io.github.tonreverse.payment.service;

import java.io.Serial;

/**
 * Another request with the same Idempotency-Key is still being processed. The API answers 409 with
 * Retry-After: retrying shortly returns the order once that request has finished.
 */
public final class IdempotencyKeyInUseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyKeyInUseException(String key) {
        super("A request with Idempotency-Key '" + key + "' is still being processed; retry shortly");
    }
}
