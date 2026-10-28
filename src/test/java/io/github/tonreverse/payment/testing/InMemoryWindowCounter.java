package io.github.tonreverse.payment.testing;

import io.github.tonreverse.payment.ratelimit.WindowCount;
import io.github.tonreverse.payment.ratelimit.WindowCounter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Same windows as RedisWindowCounter (aligned to the epoch), kept in a map. Can be told to fail, to
 * test what happens when Redis is down.
 */
public final class InMemoryWindowCounter implements WindowCounter {

    private final Clock clock;
    private final Map<String, Long> counts = new HashMap<>();
    private volatile RuntimeException failure;

    public InMemoryWindowCounter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized WindowCount increment(String key, Duration window) {
        if (failure != null) {
            throw failure;
        }
        long nowSeconds = clock.instant().getEpochSecond();
        long start = nowSeconds - nowSeconds % window.toSeconds();
        long count = counts.merge(key + ":" + start, 1L, Long::sum);
        return new WindowCount(count, Instant.ofEpochSecond(start).plus(window));
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }
}
