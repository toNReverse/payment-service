package io.github.tonreverse.payment.service;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderStatus;
import io.github.tonreverse.payment.payment.PaymentUpdate;
import io.github.tonreverse.payment.payment.PaymentUpdate.AwaitingPayment;
import io.github.tonreverse.payment.payment.PaymentUpdate.CheckoutExpired;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentAttemptFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentSucceeded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Refunded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Unrelated;

/**
 * What a payment update means for an order, as a pure function: no database, no clock, so every case
 * is a plain unit test. {@link PaymentEventService} does the I/O around it.
 */
final class PaymentEventDecisions {

    sealed interface Decision {}

    /** Move the order to {@code to}. */
    record ChangeStatus(OrderStatus to, String note) implements Decision {}

    /** Something does not add up: set the order to FLAGGED and record why. */
    record Flag(String rule, String reason) implements Decision {}

    /** Keep the event, change nothing (by design, e.g. a declined card the customer can retry). */
    record RecordOnly(String note) implements Decision {}

    /** The event does not apply (not our order, or the order has moved past it). */
    record Ignore(String note) implements Decision {}

    private PaymentEventDecisions() {}

    /** @throws PaymentEventOutOfOrderException if the event came too early and should be retried later */
    static Decision decide(Order order, PaymentUpdate update) {
        if (update instanceof Unrelated unrelated) {
            return new Ignore(unrelated.reason());
        }
        if (order == null) {
            return new Ignore("order " + update.orderId() + " does not exist");
        }
        return switch (update) {
            case PaymentSucceeded payment -> paymentSucceeded(order, payment);
            case AwaitingPayment awaiting -> forOurSession(order, awaiting.checkoutSessionId(),
                    new RecordOnly("checkout completed with a delayed payment method; waiting for the money"));
            case PaymentFailed failed -> forOurSession(order, failed.checkoutSessionId(),
                    moveTo(order, OrderStatus.FAILED, "the delayed payment failed"));
            case CheckoutExpired expired -> forOurSession(order, expired.checkoutSessionId(),
                    moveTo(order, OrderStatus.EXPIRED, "the checkout page expired unpaid"));
            // Decision (a): one failed attempt is not a failed order, the customer can retry on the same page.
            case PaymentAttemptFailed attempt -> new RecordOnly(
                    "payment attempt failed (" + attempt.reason() + "); the customer can still pay");
            case Refunded refund -> refund.fullyRefunded()
                    ? moveTo(order, OrderStatus.REFUNDED, "fully refunded " + refund.amountRefunded())
                    : new RecordOnly("partially refunded " + refund.amountRefunded() + "; the order stays " + order.status());
            case Unrelated unrelated -> new Ignore(unrelated.reason());
        };
    }

    /** Before marking an order paid, check that Stripe charged exactly what we stored, on our session. */
    private static Decision paymentSucceeded(Order order, PaymentSucceeded payment) {
        String problem = null;
        String rule = null;
        if (!isOurSession(order, payment.checkoutSessionId())) {
            rule = "checkout_session_mismatch";
            problem = "paid on checkout session " + payment.checkoutSessionId() + ", the order uses " + sessionId(order);
        } else if (!payment.amountPaid().equals(order.total())) {
            rule = "payment_amount_mismatch";
            problem = "Stripe charged " + describe(payment.amountPaid().amountMinor(), payment.amountPaid())
                    + ", the order total is " + describe(order.total().amountMinor(), order.total());
        }
        if (problem == null) {
            return moveTo(order, OrderStatus.PAID, "paid " + describe(order.total().amountMinor(), order.total()));
        }
        if (order.status().canTransitionTo(OrderStatus.FLAGGED)) {
            return new Flag(rule, problem);
        }
        return new Ignore(problem + "; the order is " + order.status() + " and cannot be flagged");
    }

    /**
     * The usual case for a status change. Already there: nothing to do (a second event with the same
     * effect). Allowed: do it. Reachable later: the event came early, retry. Otherwise it no longer applies.
     */
    private static Decision moveTo(Order order, OrderStatus target, String note) {
        OrderStatus current = order.status();
        if (current == target) {
            return new Ignore("the order is already " + target);
        }
        if (current.canTransitionTo(target)) {
            return new ChangeStatus(target, current + " -> " + target + ": " + note);
        }
        if (current.canEventuallyReach(target)) {
            throw new PaymentEventOutOfOrderException(order.id(), current, target);
        }
        return new Ignore("the order is " + current + ", so " + target + " no longer applies");
    }

    private static Decision forOurSession(Order order, String sessionId, Decision decision) {
        if (!isOurSession(order, sessionId)) {
            return new Ignore("event for checkout session " + sessionId + ", the order uses " + sessionId(order));
        }
        return decision;
    }

    private static boolean isOurSession(Order order, String sessionId) {
        CheckoutSession session = order.checkoutSession();
        return session != null && session.id().equals(sessionId);
    }

    private static String sessionId(Order order) {
        return order.checkoutSession() == null ? "none" : order.checkoutSession().id();
    }

    private static String describe(long amountMinor, io.github.tonreverse.payment.domain.Money money) {
        return amountMinor + " " + money.currency().getCurrencyCode();
    }
}
