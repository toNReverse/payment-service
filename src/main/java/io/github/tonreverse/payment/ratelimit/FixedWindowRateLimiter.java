package io.github.tonreverse.payment.ratelimit;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * At most {@code maxRequests} per client in each fixed window (e.g. 10 per minute). Fixed rather than
 * sliding windows: one atomic counter per client and window, simple to explain, at the cost of letting
 * up to twice the limit through across a window boundary. See docs/decisions.md, "Rate limiting".
 */
public final class FixedWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimiter.class);

    private final String name;
    private final WindowCounter counter;
    private final int maxRequests;
    private final Duration window;
    private final Clock clock;

    /** @param name what is being limited, part of the counter key, e.g. "orders" */
    public FixedWindowRateLimiter(String name, WindowCounter counter, int maxRequests, Duration window, Clock clock) {
        this.name = name;
        this.counter = counter;
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    /** Counts this request for {@code client} (e.g. its IP address) and says whether it may proceed. */
    public RateLimitDecision check(String client) {
        WindowCount count;
        try {
            count = counter.increment("ratelimit:" + name + ":" + client, window);
        } catch (RuntimeException e) {
            // Fail open: a shop that refuses every order while Redis is down loses more than it protects.
            log.warn("Rate limiter for {} unavailable, letting the request through: {}", name, e.getMessage());
            return RateLimitDecision.ALLOW;
        }
        if (count.count() <= maxRequests) {
            return RateLimitDecision.ALLOW;
        }
        long secondsLeft = Duration.between(clock.instant(), count.windowEnds()).toSeconds();
        return new RateLimitDecision(false, Math.max(1, secondsLeft));
    }
}
