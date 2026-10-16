package io.github.tonreverse.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.persistence.IdempotencyRecord.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JooqIdempotencyKeyRepositoryTest {

    // Microsecond precision, like Postgres: the lock timestamp is compared for equality.
    private static final Instant T0 = Instant.parse("2026-12-01T10:15:30.123456Z");
    private static final Instant T1 = T0.plusSeconds(120);
    private static final Instant T2 = T0.plusSeconds(240);
    private static final String HASH = "a".repeat(64);

    private final JooqIdempotencyKeyRepository keys = new JooqIdempotencyKeyRepository(TestDatabase.dsl());
    private final JooqOrderRepository orders = new JooqOrderRepository(TestDatabase.dsl());
    private final String key = "test-key-" + UUID.randomUUID();

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
    }

    @Test
    void reservesAnUnusedKey() {
        assertThat(keys.tryReserve(key, HASH, T0)).isTrue();

        assertThat(keys.find(key)).hasValue(new IdempotencyRecord(key, HASH, State.IN_PROGRESS, null, T0));
    }

    @Test
    void refusesASecondReservationOfTheSameKey() {
        keys.tryReserve(key, HASH, T0);

        assertThat(keys.tryReserve(key, "b".repeat(64), T1)).isFalse();
        assertThat(keys.find(key)).hasValueSatisfying(existing -> {
            assertThat(existing.requestHash()).isEqualTo(HASH);
            assertThat(existing.lockedAt()).isEqualTo(T0);
        });
    }

    @Test
    void exactlyOneOfManyConcurrentReservationsWins() throws Exception {
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return keys.tryReserve(key, HASH, T0);
                }));
            }
            start.countDown();

            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                winners += attempt.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void completingRecordsTheOrder() {
        UUID orderId = insertOrder();
        keys.tryReserve(key, HASH, T0);

        assertThat(keys.complete(key, T0, orderId)).isTrue();
        assertThat(keys.find(key)).hasValue(new IdempotencyRecord(key, HASH, State.COMPLETED, orderId, T0));
    }

    @Test
    void completingFailsOnceAnotherRequestHasTakenOver() {
        UUID orderId = insertOrder();
        keys.tryReserve(key, HASH, T0);
        keys.tryTakeOver(key, T0, T1);

        // The original holder still believes it owns the key with lock T0: its completion must not count.
        assertThat(keys.complete(key, T0, orderId)).isFalse();
        assertThat(keys.find(key)).hasValueSatisfying(record -> assertThat(record.state()).isEqualTo(State.IN_PROGRESS));
    }

    @Test
    void takingOverFailsIfTheReservationChangedSinceItWasRead() {
        keys.tryReserve(key, HASH, T0);

        assertThat(keys.tryTakeOver(key, T0, T1)).isTrue();
        // A second request that also read lock T0 loses: only one can take over.
        assertThat(keys.tryTakeOver(key, T0, T2)).isFalse();
        assertThat(keys.find(key)).hasValueSatisfying(record -> assertThat(record.lockedAt()).isEqualTo(T1));
    }

    @Test
    void releasingOnlyRemovesOurOwnReservation() {
        keys.tryReserve(key, HASH, T0);
        keys.tryTakeOver(key, T0, T1);

        keys.release(key, T0);
        assertThat(keys.find(key)).isPresent();

        keys.release(key, T1);
        assertThat(keys.find(key)).isEmpty();
    }

    @Test
    void aCompletedKeyCanNeitherBeTakenOverNorReleased() {
        UUID orderId = insertOrder();
        keys.tryReserve(key, HASH, T0);
        keys.complete(key, T0, orderId);

        assertThat(keys.tryTakeOver(key, T0, T1)).isFalse();
        keys.release(key, T0);

        assertThat(keys.find(key)).hasValueSatisfying(record -> assertThat(record.state()).isEqualTo(State.COMPLETED));
    }

    private UUID insertOrder() {
        Order order = Order.create(UUID.randomUUID(), "anna@example.com",
                List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), T0);
        orders.insert(order);
        return order.id();
    }
}
