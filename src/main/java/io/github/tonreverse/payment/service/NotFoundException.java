package io.github.tonreverse.payment.service;

import java.io.Serial;

public final class NotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
