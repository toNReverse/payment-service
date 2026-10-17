package io.github.tonreverse.payment.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.testing.InMemoryWindowCounter;
import io.github.tonreverse.payment.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    // 10:15:30: the current one-minute window ends at 10:16:00, 30 seconds from now.
    private final TestClock clock = new TestClock(Instant.parse("2026-12-01T10:15:30Z"));
    private final InMemoryWindowCounter counter = new InMemoryWindowCounter(clock);
    private final FixedWindowRateLimiter limiter =
            new FixedWindowRateLimiter("orders", counter, 3, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToTheLimitWithinAWindow() {
        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void refusesTheRestOfTheWindowAndSaysWhenToRetry() {
        for (int i = 0; i < 3; i++) {
            limiter.check("1.2.3.4");
        }

        RateLimitDecision fourth = limiter.check("1.2.3.4");

        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void theNextWindowStartsAgainFromZero() {
        for (int i = 0; i < 4; i++) {
            limiter.check("1.2.3.4");
        }

        clock.advance(Duration.ofSeconds(30));

        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }

    @Test
    void eachClientHasItsOwnLimit() {
        for (int i = 0; i < 4; i++) {
            limiter.check("1.2.3.4");
        }

        assertThat(limiter.check("5.6.7.8").allowed()).isTrue();
    }

    @Test
    void letsRequestsThroughWhenRedisIsDown() {
        // Fail open: for a shop, refusing every order while Redis is down is worse than a short
        // period without rate limiting.
        counter.failWith(new IllegalStateException("Redis unreachable"));

        assertThat(limiter.check("1.2.3.4").allowed()).isTrue();
    }
}
