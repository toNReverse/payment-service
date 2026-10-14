package io.github.tonreverse.payment.domain;

/**
 * The payment provider's hosted checkout page for one order (a Stripe Checkout Session): its id, used to
 * match webhooks to the order, and the URL the customer is sent to.
 */
public record CheckoutSession(String id, String url) {

    public CheckoutSession {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("checkout session id is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("checkout session url is required");
        }
    }
}
