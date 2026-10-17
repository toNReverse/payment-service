package io.github.tonreverse.payment.api;

import java.io.Serial;

/** STRIPE_WEBHOOK_SECRET is not set, so events cannot be verified. Answered 503. */
final class WebhooksNotConfiguredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    WebhooksNotConfiguredException() {
        super("Stripe webhooks are not configured on this instance (STRIPE_WEBHOOK_SECRET is not set)");
    }
}
