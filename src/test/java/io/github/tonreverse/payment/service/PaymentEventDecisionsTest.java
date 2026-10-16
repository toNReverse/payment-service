package io.github.tonreverse.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.domain.OrderStatus;
import io.github.tonreverse.payment.payment.PaymentUpdate.AwaitingPayment;
import io.github.tonreverse.payment.payment.PaymentUpdate.CheckoutExpired;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentAttemptFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentSucceeded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Refunded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Unrelated;
import io.github.tonreverse.payment.service.PaymentEventDecisions.ChangeStatus;
import io.github.tonreverse.payment.service.PaymentEventDecisions.Decision;
import io.github.tonreverse.payment.service.PaymentEventDecisions.Flag;
import io.github.tonreverse.payment.service.PaymentEventDecisions.Ignore;
import io.github.tonreverse.payment.service.PaymentEventDecisions.RecordOnly;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaymentEventDecisionsTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30Z");
    private static final String SESSION = "cs_test_1";
    private static final Money TOTAL = Money.of(8_000, "EUR");

    private static final Order PENDING = Order.create(UUID.randomUUID(), "anna@example.com",
                    List.of(new OrderItem("pandoro", "Pandoro 1 kg", 2, Money.of(4_000, "EUR"))), NOW)
            .startCheckout(new CheckoutSession(SESSION, "https://checkout.stripe.com/c/pay/" + SESSION), NOW);
    private static final Order PAID = PENDING.transitionTo(OrderStatus.PAID, NOW);
    private static final Order REFUNDED = PAID.transitionTo(OrderStatus.REFUNDED, NOW);
    private static final Order EXPIRED = PENDING.transitionTo(OrderStatus.EXPIRED, NOW);
    private static final UUID ID = PENDING.id();

    private static Decision decide(Order order, io.github.tonreverse.payment.payment.PaymentUpdate update) {
        return PaymentEventDecisions.decide(order, update);
    }

    @Nested
    class Payment {

        @Test
        void marksAPendingOrderPaidWhenAmountAndSessionMatch() {
            assertThat(decide(PENDING, new PaymentSucceeded(ID, SESSION, TOTAL)))
                    .isInstanceOfSatisfying(ChangeStatus.class, change -> assertThat(change.to()).isEqualTo(OrderStatus.PAID));
        }

        @Test
        void flagsTheOrderWhenStripeChargedADifferentAmount() {
            Decision decision = decide(PENDING, new PaymentSucceeded(ID, SESSION, Money.of(1_000, "EUR")));

            assertThat(decision).isInstanceOfSatisfying(Flag.class, flag -> {
                assertThat(flag.rule()).isEqualTo("payment_amount_mismatch");
                assertThat(flag.reason()).contains("1000").contains("8000");
            });
        }

        @Test
        void flagsTheOrderWhenStripeChargedInAnotherCurrency() {
            assertThat(decide(PENDING, new PaymentSucceeded(ID, SESSION, Money.of(8_000, "USD"))))
                    .isInstanceOfSatisfying(Flag.class, flag -> assertThat(flag.rule()).isEqualTo("payment_amount_mismatch"));
        }

        @Test
        void flagsTheOrderWhenThePaymentIsForAnotherCheckoutSession() {
            assertThat(decide(PENDING, new PaymentSucceeded(ID, "cs_test_other", TOTAL)))
                    .isInstanceOfSatisfying(Flag.class, flag -> assertThat(flag.rule()).isEqualTo("checkout_session_mismatch"));
        }

        @Test
        void ignoresAPaymentForAnOrderThatIsAlreadyPaid() {
            assertThat(decide(PAID, new PaymentSucceeded(ID, SESSION, TOTAL))).isInstanceOf(Ignore.class);
        }

        @Test
        void aCompletedCheckoutStillWaitingForADelayedPaymentChangesNothing() {
            assertThat(decide(PENDING, new AwaitingPayment(ID, SESSION))).isInstanceOf(RecordOnly.class);
        }
    }

    @Nested
    class Failures {

        @Test
        void aFailedCardAttemptIsRecordedButTheOrderStaysPending() {
            // Decision (a) from Milestone 2: the customer can retry on the same checkout page.
            assertThat(decide(PENDING, new PaymentAttemptFailed(ID, "Your card was declined.")))
                    .isInstanceOfSatisfying(RecordOnly.class, record -> assertThat(record.note()).contains("declined"));
        }

        @Test
        void aFailedDelayedPaymentFailsTheOrder() {
            assertThat(decide(PENDING, new PaymentFailed(ID, SESSION)))
                    .isInstanceOfSatisfying(ChangeStatus.class, change -> assertThat(change.to()).isEqualTo(OrderStatus.FAILED));
        }

        @Test
        void anExpiredCheckoutExpiresTheOrder() {
            assertThat(decide(PENDING, new CheckoutExpired(ID, SESSION)))
                    .isInstanceOfSatisfying(ChangeStatus.class, change -> assertThat(change.to()).isEqualTo(OrderStatus.EXPIRED));
        }

        @Test
        void anExpiryForAnotherSessionIsIgnored() {
            assertThat(decide(PENDING, new CheckoutExpired(ID, "cs_test_other"))).isInstanceOf(Ignore.class);
        }
    }

    @Nested
    class Refunds {

        @Test
        void aFullRefundRefundsAPaidOrder() {
            assertThat(decide(PAID, new Refunded(ID, true, TOTAL)))
                    .isInstanceOfSatisfying(ChangeStatus.class, change -> assertThat(change.to()).isEqualTo(OrderStatus.REFUNDED));
        }

        @Test
        void aPartialRefundIsRecordedAndTheOrderStaysPaid() {
            assertThat(decide(PAID, new Refunded(ID, false, Money.of(4_000, "EUR")))).isInstanceOf(RecordOnly.class);
        }
    }

    @Nested
    class OutOfOrderEvents {

        @Test
        void aRefundBeforeThePaymentIsTooEarlyAndMustBeRetried() {
            // REFUNDED can still be reached from PENDING_PAYMENT (via PAID): Stripe should send it again later.
            assertThatThrownBy(() -> decide(PENDING, new Refunded(ID, true, TOTAL)))
                    .isInstanceOf(PaymentEventOutOfOrderException.class);
        }

        @Test
        void anExpiryAfterThePaymentNoLongerApplies() {
            assertThat(decide(PAID, new CheckoutExpired(ID, SESSION))).isInstanceOf(Ignore.class);
        }

        @Test
        void aPaymentAfterTheRefundNoLongerApplies() {
            assertThat(decide(REFUNDED, new PaymentSucceeded(ID, SESSION, TOTAL))).isInstanceOf(Ignore.class);
        }

        @Test
        void aPaymentForAnExpiredOrderIsIgnoredRatherThanFlagged() {
            // EXPIRED is terminal and cannot become FLAGGED; the event is kept for a human to look at.
            assertThat(decide(EXPIRED, new PaymentSucceeded(ID, SESSION, Money.of(1, "EUR")))).isInstanceOf(Ignore.class);
        }
    }

    @Nested
    class NotOurs {

        @Test
        void unrelatedEventsAreIgnored() {
            assertThat(decide(null, new Unrelated("event type customer.created is not handled"))).isInstanceOf(Ignore.class);
        }

        @Test
        void eventsForAnOrderWeDoNotHaveAreIgnored() {
            assertThat(decide(null, new CheckoutExpired(UUID.randomUUID(), SESSION))).isInstanceOf(Ignore.class);
        }
    }
}
