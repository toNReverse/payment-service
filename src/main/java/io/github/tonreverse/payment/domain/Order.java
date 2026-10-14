package io.github.tonreverse.payment.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An order and its items. Immutable: a status change returns a new {@code Order}, so an instance can be
 * shared and logged without anyone changing it underneath.
 *
 * <p>The canonical constructor checks the invariants for every instance, including orders loaded from
 * the database: at least one item, each product listed once, a single currency, a total that equals
 * the sum of the lines, and a checkout session exactly when the order has been through checkout.
 *
 * <p>{@code id} and timestamps are passed in rather than generated here, so tests are deterministic and
 * the caller decides where time comes from (a {@code Clock} in the service).
 *
 * @param checkoutSession the payment provider's checkout page, or null before checkout starts (and for
 *     orders flagged before checkout)
 */
public record Order(
        UUID id,
        String customerEmail,
        List<OrderItem> items,
        Money total,
        OrderStatus status,
        CheckoutSession checkoutSession,
        Instant createdAt,
        Instant updatedAt) {

    public Order {
        Objects.requireNonNull(id, "id");
        if (customerEmail == null || customerEmail.isBlank()) {
            throw new IllegalArgumentException("customerEmail is required");
        }
        items = List.copyOf(items);
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        requireCheckoutSessionMatchesStatus(status, checkoutSession);
        requireEachProductOnce(items);
        Money itemsTotal = sumOf(items);
        if (!itemsTotal.equals(total)) {
            throw new IllegalArgumentException("total " + total + " does not match the items, which sum to " + itemsTotal);
        }
    }

    /** A new order, before any payment has been attempted. */
    public static Order create(UUID id, String customerEmail, List<OrderItem> items, Instant now) {
        return new Order(id, customerEmail, items, sumOf(items), OrderStatus.CREATED, null, now, now);
    }

    /**
     * The customer can now pay on the provider's page. This is the only way into PENDING_PAYMENT, because
     * a pending order without a session would have no page to pay on.
     */
    public Order startCheckout(CheckoutSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        return new Order(id, customerEmail, items, total, status.transitionTo(OrderStatus.PENDING_PAYMENT), session,
                createdAt, now);
    }

    /** @throws InvalidStatusTransitionException if the state machine does not allow the change */
    public Order transitionTo(OrderStatus next, Instant now) {
        return new Order(id, customerEmail, items, total, status.transitionTo(next), checkoutSession, createdAt, now);
    }

    /** Money.plus rejects mixed currencies, so this also enforces a single currency per order. */
    private static Money sumOf(List<OrderItem> items) {
        return items.stream()
                .map(OrderItem::lineTotal)
                .reduce(Money::plus)
                .orElseThrow(() -> new IllegalArgumentException("an order needs at least one item"));
    }

    private static void requireCheckoutSessionMatchesStatus(OrderStatus status, CheckoutSession session) {
        boolean pastCheckout = switch (status) {
            case CREATED, FLAGGED -> false;
            case PENDING_PAYMENT, PAID, FAILED, EXPIRED, REFUNDED -> true;
        };
        if (pastCheckout && session == null) {
            throw new IllegalArgumentException("a " + status + " order needs a checkout session; use startCheckout");
        }
        if (status == OrderStatus.CREATED && session != null) {
            throw new IllegalArgumentException("a CREATED order has no checkout session yet");
        }
    }

    private static void requireEachProductOnce(List<OrderItem> items) {
        Set<String> seen = new HashSet<>();
        for (OrderItem item : items) {
            if (!seen.add(item.productId())) {
                throw new IllegalArgumentException("product " + item.productId() + " is listed more than once");
            }
        }
    }
}
