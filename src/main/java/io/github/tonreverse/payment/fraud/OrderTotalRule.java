package io.github.tonreverse.payment.fraud;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import java.util.Optional;

/** Flags orders above a configured total: an unusually large basket is a common sign of a stolen card. */
public final class OrderTotalRule implements FraudRule {

    private final Money limit;

    public OrderTotalRule(Money limit) {
        this.limit = limit;
    }

    @Override
    public String name() {
        return "order_total_above_limit";
    }

    @Override
    public Optional<String> check(Order order) {
        // The limit is set in one currency; amounts in another cannot be compared without exchange rates.
        if (!order.total().currency().equals(limit.currency())) {
            return Optional.empty();
        }
        if (!order.total().isGreaterThan(limit)) {
            return Optional.empty();
        }
        String currency = limit.currency().getCurrencyCode();
        return Optional.of("order total " + order.total().amountMinor() + " " + currency
                + " is above the limit of " + limit.amountMinor() + " " + currency);
    }
}
