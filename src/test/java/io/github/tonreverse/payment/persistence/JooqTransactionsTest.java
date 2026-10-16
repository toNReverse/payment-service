package io.github.tonreverse.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JooqTransactionsTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30.123456Z");

    private final JooqTransactions transactions = new JooqTransactions(TestDatabase.dsl());
    private final JooqOrderRepository orders = new JooqOrderRepository(TestDatabase.dsl());
    private final JooqIdempotencyKeyRepository keys = new JooqIdempotencyKeyRepository(TestDatabase.dsl());

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
    }

    @Test
    void commitsWhenTheWorkReturns() {
        Order order = newOrder();

        transactions.inTransaction(() -> orders.insert(order));

        assertThat(orders.findById(order.id())).isPresent();
    }

    @Test
    void rollsBackEveryRepositoryCallWhenTheWorkThrows() {
        Order order = newOrder();
        String key = "test-key-" + UUID.randomUUID();
        RuntimeException failure = new IllegalStateException("fails after both writes");

        assertThatThrownBy(() -> transactions.inTransaction(() -> {
            keys.tryReserve(key, "a".repeat(64), NOW);
            orders.insert(order);
            throw failure;
        })).isSameAs(failure);

        // Two repositories, one transaction: neither write survived.
        assertThat(orders.findById(order.id())).isEmpty();
        assertThat(keys.find(key)).isEmpty();
    }

    private static Order newOrder() {
        return Order.create(UUID.randomUUID(), "anna@example.com",
                List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), NOW);
    }
}
