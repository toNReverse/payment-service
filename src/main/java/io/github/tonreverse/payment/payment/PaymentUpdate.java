package io.github.tonreverse.payment.payment;

import io.github.tonreverse.payment.domain.Money;
import java.util.UUID;

/**
 * What a payment provider tells us about an order, in our terms rather than Stripe's. The parser turns
 * Stripe events into these; the service decides what each means for the order. Nothing here knows about
 * Stripe event types, and nothing in the service does either.
 */
public sealed interface PaymentUpdate {

    /** The order concerned, or null if the event is not about one of our orders. */
    UUID orderId();

    /** The customer paid; {@code amountPaid} is what the provider charged, to compare with the order. */
    record PaymentSucceeded(UUID orderId, String checkoutSessionId, Money amountPaid) implements PaymentUpdate {}

    /** Checkout finished with a delayed payment method (e.g. SEPA); the money is confirmed later. */
    record AwaitingPayment(UUID orderId, String checkoutSessionId) implements PaymentUpdate {}

    /** A delayed payment failed for good. */
    record PaymentFailed(UUID orderId, String checkoutSessionId) implements PaymentUpdate {}

    /** The customer never paid and the checkout page expired. */
    record CheckoutExpired(UUID orderId, String checkoutSessionId) implements PaymentUpdate {}

    /** One attempt failed (e.g. card declined); the customer can still pay on the same checkout page. */
    record PaymentAttemptFailed(UUID orderId, String reason) implements PaymentUpdate {}

    /** Money was given back; {@code fullyRefunded} is false for a partial refund. */
    record Refunded(UUID orderId, boolean fullyRefunded, Money amountRefunded) implements PaymentUpdate {}

    /** Not about any of our orders, or an event type we do not act on. */
    record Unrelated(String reason) implements PaymentUpdate {

        @Override
        public UUID orderId() {
            return null;
        }
    }
}
