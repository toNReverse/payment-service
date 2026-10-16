package io.github.tonreverse.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.domain.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JooqOrderRepositoryTest {

    // Postgres stores microseconds, so test timestamps use exactly that precision.
    private static final Instant CREATED_AT = Instant.parse("2026-12-01T10:15:30.123456Z");

    private final JooqOrderRepository repository = new JooqOrderRepository(TestDatabase.dsl());

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
    }

    @Test
    void savesAnOrderWithItsItemsAndLoadsItBack() {
        // Items are listed in product-id order, the order the repository loads them in.
        Order order = Order.create(UUID.randomUUID(), "anna@example.com", List.of(
                new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR")),
                new OrderItem("panettone-classico", "Panettone Classico 1 kg", 2, Money.of(2_800, "EUR"))), CREATED_AT);

        repository.insert(order);

        assertThat(repository.findById(order.id())).hasValue(order);
    }

    @Test
    void keepsStatusCheckoutSessionAndBothTimestamps() {
        Instant updatedAt = CREATED_AT.plusSeconds(90);
        Order order = new Order(UUID.randomUUID(), "anna@example.com",
                List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), Money.of(2_600, "EUR"),
                OrderStatus.PENDING_PAYMENT,
                new CheckoutSession("cs_test_a1b2c3", "https://checkout.stripe.com/c/pay/cs_test_a1b2c3"),
                CREATED_AT, updatedAt);

        repository.insert(order);

        assertThat(repository.findById(order.id())).hasValue(order);
    }

    @Test
    void updatesTheStatusAndTheUpdateTime() {
        Order pending = Order.create(UUID.randomUUID(), "anna@example.com",
                        List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), CREATED_AT)
                .startCheckout(new CheckoutSession("cs_test_1", "https://checkout.stripe.com/c/pay/cs_test_1"), CREATED_AT);
        repository.insert(pending);
        Order paid = pending.transitionTo(OrderStatus.PAID, CREATED_AT.plusSeconds(30));

        repository.updateStatus(paid);

        assertThat(repository.findById(paid.id())).hasValue(paid);
    }

    @Test
    void lockingAnOrderMakesASecondTransactionWaitForTheFirst() throws Exception {
        // Two webhooks for the same order must not interleave: each reads the order FOR UPDATE.
        Order order = Order.create(UUID.randomUUID(), "anna@example.com",
                List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), CREATED_AT);
        repository.insert(order);
        JooqTransactions transactions = new JooqTransactions(TestDatabase.dsl());
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        Duration held = Duration.ofMillis(600);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> first = pool.submit(() -> transactions.inTransaction(() -> {
                repository.findByIdForUpdate(order.id());
                firstHoldsTheLock.countDown();
                sleep(held);
            }));
            firstHoldsTheLock.await();

            long start = System.nanoTime();
            transactions.inTransaction(() -> assertThat(repository.findByIdForUpdate(order.id())).isPresent());
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            first.get();
            assertThat(waited).isGreaterThan(held.dividedBy(2));
        }
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void savesNothingWhenAnyItemFails() {
        // The second item references a product that does not exist, so its insert violates the foreign key.
        // The order row was inserted first and must be rolled back with it.
        Order order = Order.create(UUID.randomUUID(), "anna@example.com", List.of(
                new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR")),
                new OrderItem("test-does-not-exist", "Does not exist", 1, Money.of(1_000, "EUR"))), CREATED_AT);

        assertThatThrownBy(() -> repository.insert(order)).isInstanceOf(DataAccessException.class);
        assertThat(repository.findById(order.id())).isEmpty();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
