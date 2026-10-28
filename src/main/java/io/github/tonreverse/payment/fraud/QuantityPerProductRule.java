package io.github.tonreverse.payment.fraud;

import io.github.tonreverse.payment.domain.Order;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Flags orders with an unusual quantity of one product (forty pandori is a reseller or a stolen card,
 * rarely a family). Separate from request validation, which only rejects absurd values (over 1000):
 * a high quantity is allowed, but a human should look at it before the customer pays.
 */
public final class QuantityPerProductRule implements FraudRule {

    private final int maxQuantity;

    public QuantityPerProductRule(int maxQuantity) {
        this.maxQuantity = maxQuantity;
    }

    @Override
    public String name() {
        return "quantity_above_limit";
    }

    @Override
    public Optional<String> check(Order order) {
        String excessive = order.items().stream()
                .filter(item -> item.quantity() > maxQuantity)
                .map(item -> item.productId() + " x" + item.quantity())
                .collect(Collectors.joining(", "));
        if (excessive.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("quantity above the limit of " + maxQuantity + " per product: " + excessive);
    }
}
