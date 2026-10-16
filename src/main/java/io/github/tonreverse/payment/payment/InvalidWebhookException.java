package io.github.tonreverse.payment.payment;

import java.io.Serial;

/** The webhook request is not a genuine Stripe event: bad or missing signature, or not an event. Answered 400. */
public final class InvalidWebhookException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidWebhookException(String message) {
        super(message);
    }
}
