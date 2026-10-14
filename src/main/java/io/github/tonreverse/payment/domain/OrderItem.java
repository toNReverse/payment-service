package io.github.tonreverse.payment.domain;

import java.util.Objects;

/**
 * One line of an order. The unit price is copied from the product when the order is placed, so later
 * price changes in the catalog do not change what the customer was charged.
 */
public record OrderItem(String productId, int quantity, Money unitPrice) {

    public OrderItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
