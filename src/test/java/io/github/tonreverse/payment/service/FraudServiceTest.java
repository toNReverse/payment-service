package io.github.tonreverse.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.fraud.FraudFinding;
import io.github.tonreverse.payment.fraud.FraudRule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FraudServiceTest {

    private static final Order ORDER = Order.create(UUID.randomUUID(), "anna@example.com",
            List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), Instant.parse("2026-12-01T10:15:30Z"));

    private static FraudRule rule(String name, Optional<String> result) {
        return new FraudRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<String> check(Order order) {
                return result;
            }
        };
    }

    private static FraudRule broken(String name) {
        return new FraudRule() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<String> check(Order order) {
                throw new IllegalStateException("Redis unreachable");
            }
        };
    }

    @Test
    void reportsEveryRuleThatFiresNotJustTheFirst() {
        FraudService fraud = new FraudService(List.of(
                rule("first", Optional.of("reason one")),
                rule("clean", Optional.empty()),
                rule("second", Optional.of("reason two"))));

        assertThat(fraud.evaluate(ORDER)).containsExactly(
                new FraudFinding("first", "reason one"), new FraudFinding("second", "reason two"));
    }

    @Test
    void aRuleThatCannotRunIsSkippedAndTheOthersStillApply() {
        // Fail open, like the rate limiter: Redis being down must not block every order.
        FraudService fraud = new FraudService(List.of(broken("orders_per_email_above_limit"), rule("total", Optional.of("too much"))));

        assertThat(fraud.evaluate(ORDER)).containsExactly(new FraudFinding("total", "too much"));
    }

    @Test
    void withoutRulesNothingIsFlagged() {
        assertThat(FraudService.none().evaluate(ORDER)).isEmpty();
    }
}
