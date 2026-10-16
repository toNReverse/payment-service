package io.github.tonreverse.payment.domain;

import java.util.Objects;

/**
 * One line of an order. Name and unit price are copied from the product when the order is placed, so
 * later catalog changes do not change what the customer bought, saw on the checkout page, or was charged.
 */
public record OrderItem(String productId, String productName, int quantity, Money unitPrice) {

    public OrderItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName is required");
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
