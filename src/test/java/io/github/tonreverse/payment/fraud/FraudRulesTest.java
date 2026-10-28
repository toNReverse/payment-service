package io.github.tonreverse.payment.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.ratelimit.WindowCount;
import io.github.tonreverse.payment.ratelimit.WindowCounter;
import io.github.tonreverse.payment.testing.InMemoryWindowCounter;
import io.github.tonreverse.payment.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FraudRulesTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30Z");

    private static Order order(String email, OrderItem... items) {
        return Order.create(UUID.randomUUID(), email, List.of(items), NOW);
    }

    private static OrderItem pandoro(int quantity) {
        return new OrderItem("pandoro", "Pandoro 1 kg", quantity, Money.of(2_600, "EUR"));
    }

    @Nested
    class OrderTotal {

        private final OrderTotalRule rule = new OrderTotalRule(Money.of(50_000, "EUR"));

        @Test
        void passesAtOrBelowTheLimit() {
            assertThat(rule.check(order("anna@example.com", new OrderItem("x", "X", 1, Money.of(50_000, "EUR"))))).isEmpty();
        }

        @Test
        void flagsAnOrderAboveTheLimit() {
            assertThat(rule.check(order("anna@example.com", new OrderItem("x", "X", 1, Money.of(50_001, "EUR")))))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("50001").contains("50000"));
            assertThat(rule.name()).isEqualTo("order_total_above_limit");
        }

        @Test
        void doesNotCompareAmountsInDifferentCurrencies() {
            assertThat(rule.check(order("anna@example.com", new OrderItem("x", "X", 1, Money.of(900_000, "USD"))))).isEmpty();
        }
    }

    @Nested
    class QuantityPerProduct {

        private final QuantityPerProductRule rule = new QuantityPerProductRule(20);

        @Test
        void passesUpToTheLimit() {
            assertThat(rule.check(order("anna@example.com", pandoro(20)))).isEmpty();
        }

        @Test
        void flagsAProductOrderedInUnusualQuantity() {
            assertThat(rule.check(order("anna@example.com", pandoro(21))))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("pandoro").contains("21").contains("20"));
            assertThat(rule.name()).isEqualTo("quantity_above_limit");
        }
    }

    @Nested
    class OrdersPerEmail {

        private final TestClock clock = new TestClock(NOW);
        private final OrdersPerEmailRule rule =
                new OrdersPerEmailRule(new InMemoryWindowCounter(clock), 3, Duration.ofHours(1));

        @Test
        void flagsTheFirstOrderBeyondTheLimitInTheWindow() {
            assertThat(rule.check(order("anna@example.com", pandoro(1)))).isEmpty();
            assertThat(rule.check(order("anna@example.com", pandoro(1)))).isEmpty();
            assertThat(rule.check(order("anna@example.com", pandoro(1)))).isEmpty();

            assertThat(rule.check(order("anna@example.com", pandoro(1))))
                    .hasValueSatisfying(reason -> assertThat(reason).contains("4").contains("3"));
            assertThat(rule.name()).isEqualTo("orders_per_email_above_limit");
        }

        @Test
        void countsEachEmailSeparately() {
            for (int i = 0; i < 3; i++) {
                rule.check(order("anna@example.com", pandoro(1)));
            }

            assertThat(rule.check(order("bruno@example.com", pandoro(1)))).isEmpty();
        }

        @Test
        void startsCountingAgainInTheNextWindow() {
            for (int i = 0; i < 4; i++) {
                rule.check(order("anna@example.com", pandoro(1)));
            }

            clock.advance(Duration.ofHours(1));

            assertThat(rule.check(order("anna@example.com", pandoro(1)))).isEmpty();
        }

        @Test
        void keepsTheEmailOutOfRedis() {
            List<String> keys = new ArrayList<>();
            WindowCounter recording = (key, window) -> {
                keys.add(key);
                return new WindowCount(1, NOW.plus(window));
            };

            new OrdersPerEmailRule(recording, 3, Duration.ofHours(1)).check(order("anna@example.com", pandoro(1)));

            // A hash identifies the customer for counting without storing their address.
            assertThat(keys).singleElement().satisfies(key -> {
                assertThat(key).doesNotContain("anna").doesNotContain("example.com");
                assertThat(key).matches("fraud:orders-per-email:[0-9a-f]{64}");
            });
        }
    }
}
