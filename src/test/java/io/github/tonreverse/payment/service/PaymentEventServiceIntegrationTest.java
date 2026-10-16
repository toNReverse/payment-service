package io.github.tonreverse.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.domain.OrderStatus;
import io.github.tonreverse.payment.payment.PaymentNotification;
import io.github.tonreverse.payment.payment.PaymentUpdate;
import io.github.tonreverse.payment.payment.PaymentUpdate.CheckoutExpired;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentAttemptFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentSucceeded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Refunded;
import io.github.tonreverse.payment.persistence.JooqFraudFlagRepository;
import io.github.tonreverse.payment.persistence.JooqOrderRepository;
import io.github.tonreverse.payment.persistence.JooqPaymentEventRepository;
import io.github.tonreverse.payment.persistence.JooqTransactions;
import io.github.tonreverse.payment.persistence.PaymentEventRecord;
import io.github.tonreverse.payment.persistence.PaymentEventRecord.Outcome;
import io.github.tonreverse.payment.persistence.TestDatabase;
import io.github.tonreverse.payment.service.PaymentEventService.Result;
import io.github.tonreverse.payment.testing.TestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Webhook processing against Postgres: one transaction per event, row locks and the unique event id. */
@Tag("integration")
class PaymentEventServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30.123456Z");
    private static final Money TOTAL = Money.of(5_200, "EUR");

    private final DSLContext dsl = TestDatabase.dsl();
    private final JooqOrderRepository orders = new JooqOrderRepository(dsl);
    private final JooqPaymentEventRepository events = new JooqPaymentEventRepository(dsl);
    private final JooqFraudFlagRepository fraudFlags = new JooqFraudFlagRepository(dsl);
    private final PaymentEventService service = new PaymentEventService(
            orders, events, fraudFlags, new JooqTransactions(dsl), new TestClock(NOW), UUID::randomUUID);

    private Order order;
    private String session;

    @BeforeEach
    void createAPendingOrder() {
        TestDatabase.deleteTestData();
        UUID id = UUID.randomUUID();
        session = "cs_test_" + id.toString().replace("-", "");
        order = Order.create(id, "anna@example.com",
                        List.of(new OrderItem("pandoro", "Pandoro 1 kg", 2, Money.of(2_600, "EUR"))), NOW)
                .startCheckout(new CheckoutSession(session, "https://checkout.stripe.com/c/pay/" + session), NOW);
        orders.insert(order);
    }

    private static PaymentNotification event(String eventId, PaymentUpdate update) {
        return new PaymentNotification(eventId, "test.event", "{\"id\": \"" + eventId + "\"}", update);
    }

    private PaymentNotification payment(String eventId) {
        return event(eventId, new PaymentSucceeded(order.id(), session, TOTAL));
    }

    private OrderStatus status() {
        return orders.findById(order.id()).orElseThrow().status();
    }

    @Test
    void aPaymentMarksTheOrderPaidAndRecordsTheEvent() {
        assertThat(service.apply(payment("evt_paid"))).isEqualTo(Result.APPLIED);

        assertThat(status()).isEqualTo(OrderStatus.PAID);
        assertThat(events.findByOrderId(order.id()))
                .extracting(PaymentEventRecord::eventId, PaymentEventRecord::outcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("evt_paid", Outcome.APPLIED));
    }

    @Test
    void aRedeliveredEventIsAcknowledgedWithoutBeingAppliedAgain() {
        service.apply(payment("evt_paid"));
        service.apply(event("evt_refund", new Refunded(order.id(), true, TOTAL)));

        // Stripe delivers the payment event once more, after the refund.
        assertThat(service.apply(payment("evt_paid"))).isEqualTo(Result.DUPLICATE);

        assertThat(status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(events.findByOrderId(order.id())).hasSize(2);
    }

    @Test
    void aPaymentOfTheWrongAmountFlagsTheOrderWithTheReason() {
        PaymentNotification wrongAmount = event("evt_paid", new PaymentSucceeded(order.id(), session, Money.of(100, "EUR")));

        assertThat(service.apply(wrongAmount)).isEqualTo(Result.FLAGGED);

        assertThat(status()).isEqualTo(OrderStatus.FLAGGED);
        assertThat(fraudFlags.findByOrderId(order.id())).singleElement().satisfies(flag -> {
            assertThat(flag.rule()).isEqualTo("payment_amount_mismatch");
            assertThat(flag.reason()).contains("100 EUR").contains("5200 EUR");
        });
    }

    @Test
    void aRefundArrivingBeforeThePaymentIsRefusedUntilThePaymentArrives() {
        PaymentNotification refund = event("evt_refund", new Refunded(order.id(), true, TOTAL));

        assertThatThrownBy(() -> service.apply(refund)).isInstanceOf(PaymentEventOutOfOrderException.class);
        // Nothing stored, so Stripe's next delivery of the same event is processed normally.
        assertThat(events.findByOrderId(order.id())).isEmpty();
        assertThat(status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        service.apply(payment("evt_paid"));
        assertThat(service.apply(refund)).isEqualTo(Result.APPLIED);
        assertThat(status()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void aDeclinedCardIsRecordedAndTheOrderStaysPending() {
        PaymentNotification declined = event("evt_failed", new PaymentAttemptFailed(order.id(), "Your card was declined."));

        assertThat(service.apply(declined)).isEqualTo(Result.RECORDED);
        assertThat(status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        // The customer tries another card on the same page, and this time it works.
        assertThat(service.apply(payment("evt_paid"))).isEqualTo(Result.APPLIED);
    }

    @Test
    void anEventForAnUnknownOrderIsKeptAsIgnored() {
        PaymentNotification foreign = event("evt_foreign", new CheckoutExpired(UUID.randomUUID(), "cs_test_other"));

        assertThat(service.apply(foreign)).isEqualTo(Result.IGNORED);
        assertThat(service.apply(foreign)).isEqualTo(Result.DUPLICATE);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyItOnce() throws Exception {
        List<Result> results = runConcurrently(8, () -> service.apply(payment("evt_paid")));

        assertThat(results).containsOnlyOnce(Result.APPLIED);
        assertThat(results).filteredOn(result -> result != Result.APPLIED).containsOnly(Result.DUPLICATE);
        assertThat(events.findByOrderId(order.id())).hasSize(1);
    }

    @Test
    void twoDifferentEventsForTheSameOrderAreHandledOneAfterTheOther() throws Exception {
        // Two distinct payment events (e.g. a retried webhook endpoint configuration sending a copy with a
        // new id). Thanks to the row lock the second sees PAID and is ignored instead of applying again.
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Result> first = pool.submit(() -> {
                start.await();
                return service.apply(payment("evt_paid_1"));
            });
            Future<Result> second = pool.submit(() -> {
                start.await();
                return service.apply(payment("evt_paid_2"));
            });
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(Result.APPLIED, Result.IGNORED);
        }
        assertThat(status()).isEqualTo(OrderStatus.PAID);
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
