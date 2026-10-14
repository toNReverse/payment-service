package io.github.tonreverse.payment.service;

import java.io.Serial;
import java.util.List;

/** The request is not acceptable as sent. Carries every problem found, so the client can fix them at once. */
public final class ValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    // Exceptions are Serializable because Throwable is; they are never serialized here, hence transient.
    private final transient List<String> details;

    public ValidationException(List<String> details) {
        super("Validation failed: " + String.join("; ", details));
        this.details = List.copyOf(details);
    }

    public ValidationException(String detail) {
        this(List.of(detail));
    }

    public List<String> details() {
        return details;
    }
}
