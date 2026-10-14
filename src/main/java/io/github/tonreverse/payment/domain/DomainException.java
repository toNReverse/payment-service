package io.github.tonreverse.payment.domain;

import java.io.Serial;

/**
 * A business rule was broken. Unchecked, like most Java domain code: callers cannot recover from it
 * locally, and the API layer maps it to an HTTP status in one place.
 */
public abstract class DomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    protected DomainException(String message) {
        super(message);
    }
}
