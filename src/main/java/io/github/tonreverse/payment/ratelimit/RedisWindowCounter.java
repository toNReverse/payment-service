package io.github.tonreverse.payment.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.Response;

/**
 * Fixed-window counters in Redis (see docs/decisions.md, "Rate limiting"). Windows are aligned to the
 * epoch (a one-minute window runs from :00 to :00), and each window has its own key,
 * e.g. {@code ratelimit:orders:ip:1.2.3.4:1796120100}.
 *
 * <p>INCR and EXPIRE are sent together in MULTI/EXEC, so Redis applies them as one step: the count is
 * never lost under concurrency, and a key never ends up without an expiry. The expiry is reset on each
 * event to one window from now, which always outlasts the window itself; after that the key is garbage.
 */
public final class RedisWindowCounter implements WindowCounter {

    private final RedisClient redis;
    private final Clock clock;

    public RedisWindowCounter(RedisClient redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public WindowCount increment(String key, Duration window) {
        Instant now = clock.instant();
        String redisKey = redisKey(key, window, now);
        try (AbstractTransaction transaction = redis.multi()) {
            Response<Long> count = transaction.incr(redisKey);
            transaction.expire(redisKey, window.toSeconds());
            transaction.exec();
            return new WindowCount(count.get(), windowStart(window, now).plus(window));
        }
    }

    static String redisKey(String key, Duration window, Instant now) {
        return key + ":" + windowStart(window, now).getEpochSecond();
    }

    private static Instant windowStart(Duration window, Instant now) {
        long windowSeconds = window.toSeconds();
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("a window must last at least one second");
        }
        long nowSeconds = now.getEpochSecond();
        return Instant.ofEpochSecond(nowSeconds - nowSeconds % windowSeconds);
    }
}
