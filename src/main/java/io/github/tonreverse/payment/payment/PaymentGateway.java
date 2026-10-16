package io.github.tonreverse.payment.payment;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Order;

/**
 * The payment provider, behind an interface so services can be tested without calling Stripe. Milestone 5
 * adds the Stripe implementation; until then {@link FakePaymentGateway} stands in.
 */
public interface PaymentGateway {

    /**
     * Creates a hosted checkout page where the customer pays {@code order.total()}.
     *
     * @throws PaymentGatewayException if the provider fails or cannot be reached
     */
    CheckoutSession createCheckoutSession(Order order);
}
