package io.github.tonreverse.payment.payment;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Order;

/**
 * Stand-in until the Stripe gateway exists (Milestone 5): makes up a session without any network call.
 * The URL uses the reserved .invalid domain, so it can never point at a real site.
 */
public final class FakePaymentGateway implements PaymentGateway {

    @Override
    public CheckoutSession createCheckoutSession(Order order) {
        String sessionId = "cs_fake_" + order.id().toString().replace("-", "");
        return new CheckoutSession(sessionId, "https://checkout.example.invalid/pay/" + sessionId);
    }
}
