package io.github.tonreverse.payment.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.testing.TestClock;
import io.github.tonreverse.payment.testing.TestRedis;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class RedisWindowCounterIntegrationTest {

    // 10:15:30 falls in the one-minute window 10:15:00 - 10:16:00.
    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30Z");
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final TestClock clock = new TestClock(NOW);
    private final RedisWindowCounter counter = new RedisWindowCounter(TestRedis.client(), clock);

    @BeforeEach
    void emptyRedis() {
        TestRedis.flush();
    }

    @Test
    void countsEventsForAKeyWithinTheCurrentWindow() {
        assertThat(counter.increment("test:ip:1.2.3.4", MINUTE).count()).isEqualTo(1);
        assertThat(counter.increment("test:ip:1.2.3.4", MINUTE).count()).isEqualTo(2);

        WindowCount third = counter.increment("test:ip:1.2.3.4", MINUTE);
        assertThat(third.count()).isEqualTo(3);
        assertThat(third.windowEnds()).isEqualTo(Instant.parse("2026-12-01T10:16:00Z"));
    }

    @Test
    void keysAreCountedSeparately() {
        counter.increment("test:ip:1.2.3.4", MINUTE);

        assertThat(counter.increment("test:ip:5.6.7.8", MINUTE).count()).isEqualTo(1);
    }

    @Test
    void theNextWindowStartsFromOne() {
        counter.increment("test:ip:1.2.3.4", MINUTE);
        counter.increment("test:ip:1.2.3.4", MINUTE);

        clock.advance(Duration.ofSeconds(30)); // 10:16:00, a new window

        assertThat(counter.increment("test:ip:1.2.3.4", MINUTE).count()).isEqualTo(1);
    }

    @Test
    void theRedisKeyExpiresWithItsWindow() {
        counter.increment("test:ip:1.2.3.4", MINUTE);

        // Without the expiry an unused counter would stay in Redis forever.
        long ttl = TestRedis.client().ttl(RedisWindowCounter.redisKey("test:ip:1.2.3.4", MINUTE, NOW));
        assertThat(ttl).isBetween(1L, MINUTE.toSeconds());
    }

    @Test
    void concurrentIncrementsAreNeverLost() throws Exception {
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return counter.increment("test:ip:1.2.3.4", MINUTE).count();
                }));
            }
            start.countDown();

            List<Long> counts = new ArrayList<>();
            for (Future<Long> result : results) {
                counts.add(result.get());
            }
            // INCR is atomic: every request got a different number, 1 to 20.
            assertThat(counts).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.LongStream.rangeClosed(1, threads).boxed().toList());
        }
    }
}
