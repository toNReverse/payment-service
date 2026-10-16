package io.github.tonreverse.payment.testing;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.payment.PaymentGateway;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Counts calls, can fail on demand, and can run a hook during the call (to simulate a slow provider). */
public final class StubPaymentGateway implements PaymentGateway {

    private final AtomicInteger calls = new AtomicInteger();
    private volatile RuntimeException failure;
    private volatile Consumer<Order> duringCall = order -> {};

    @Override
    public CheckoutSession createCheckoutSession(Order order) {
        calls.incrementAndGet();
        duringCall.accept(order);
        if (failure != null) {
            throw failure;
        }
        return sessionFor(order);
    }

    /** The session this stub returns for an order, so tests can predict it. */
    public static CheckoutSession sessionFor(Order order) {
        String id = "cs_test_" + order.id().toString().replace("-", "");
        return new CheckoutSession(id, "https://checkout.test.invalid/pay/" + id);
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void succeed() {
        this.failure = null;
    }

    public void duringCall(Consumer<Order> hook) {
        this.duringCall = hook;
    }

    public int calls() {
        return calls.get();
    }
}
