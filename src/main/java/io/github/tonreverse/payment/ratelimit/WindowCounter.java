package io.github.tonreverse.payment.ratelimit;

import java.time.Duration;

/**
 * Counts events per key in fixed time windows: the building block for both the per-IP rate limit and
 * the orders-per-email fraud rule. An interface so tests can count in memory instead of in Redis.
 */
public interface WindowCounter {

    /**
     * Counts one more event for {@code key} in the current window and returns the new total.
     *
     * @throws RuntimeException if the store cannot be reached; callers decide whether to fail open
     */
    WindowCount increment(String key, Duration window);
}
