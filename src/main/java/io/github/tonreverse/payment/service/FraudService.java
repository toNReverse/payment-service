package io.github.tonreverse.payment.service;

import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.fraud.FraudFinding;
import io.github.tonreverse.payment.fraud.FraudRule;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every fraud rule on a new order and collects the ones that fire. All rules run, not just until
 * the first hit, so the reviewer sees every reason an order looked suspicious.
 */
public final class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    private final List<FraudRule> rules;

    public FraudService(List<FraudRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static FraudService none() {
        return new FraudService(List.of());
    }

    public List<FraudFinding> evaluate(Order order) {
        List<FraudFinding> findings = new ArrayList<>();
        for (FraudRule rule : rules) {
            try {
                rule.check(order).ifPresent(reason -> findings.add(new FraudFinding(rule.name(), reason)));
            } catch (RuntimeException e) {
                // Fail open, like the rate limiter: a rule that cannot run (Redis down) must not block every
                // order. The other rules still apply.
                log.warn("Fraud rule {} could not run for order {}, skipping it: {}", rule.name(), order.id(), e.getMessage());
            }
        }
        return findings;
    }
}
