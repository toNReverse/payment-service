package io.github.tonreverse.payment.service;

import io.github.tonreverse.payment.domain.OrderStatus;
import java.io.Serial;
import java.util.UUID;

/**
 * The event arrived before the one it depends on (Stripe does not guarantee delivery order), e.g. a refund
 * before the payment. Nothing is stored; the webhook answers 409 so that Stripe delivers it again later,
 * by which time the earlier event has usually arrived.
 */
public final class PaymentEventOutOfOrderException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PaymentEventOutOfOrderException(UUID orderId, OrderStatus current, OrderStatus target) {
        super("order " + orderId + " is " + current + "; " + target + " can only follow a later event");
    }
}
