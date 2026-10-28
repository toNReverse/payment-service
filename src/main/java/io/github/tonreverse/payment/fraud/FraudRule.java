package io.github.tonreverse.payment.fraud;

import io.github.tonreverse.payment.domain.Order;
import java.util.Optional;

/**
 * One fraud check, run when an order is placed, before any payment page is created. Each rule is a small
 * class so it can be read, tested and tuned on its own; FraudService runs them all.
 */
public interface FraudRule {

    /** Short machine-readable name stored with the flag, e.g. "order_total_above_limit". */
    String name();

    /** Why the order looks suspicious, or empty if it passes. */
    Optional<String> check(Order order);
}
