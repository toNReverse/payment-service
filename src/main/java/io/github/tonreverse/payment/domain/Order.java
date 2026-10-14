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
 * the database: at least one item, each product listed once, a single currency, and a total that
 * equals the sum of the lines.
 *
 * <p>{@code id} and timestamps are passed in rather than generated here, so tests are deterministic and
 * the caller decides where time comes from (a {@code Clock} in the service).
 *
 * @param checkoutSessionId the payment provider's checkout session (Stripe), or null before checkout starts
 */
public record Order(
        UUID id,
        String customerEmail,
        List<OrderItem> items,
        Money total,
        OrderStatus status,
        String checkoutSessionId,
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

    /** @throws InvalidStatusTransitionException if the state machine does not allow the change */
    public Order transitionTo(OrderStatus next, Instant now) {
        return new Order(id, customerEmail, items, total, status.transitionTo(next), checkoutSessionId, createdAt, now);
    }

    /** Money.plus rejects mixed currencies, so this also enforces a single currency per order. */
    private static Money sumOf(List<OrderItem> items) {
        return items.stream()
                .map(OrderItem::lineTotal)
                .reduce(Money::plus)
                .orElseThrow(() -> new IllegalArgumentException("an order needs at least one item"));
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
