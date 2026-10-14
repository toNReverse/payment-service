package io.github.tonreverse.payment.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;

public final class RedisHealthCheck implements HealthCheck {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthCheck.class);

    private final RedisClient redis;

    public RedisHealthCheck(RedisClient redis) {
        this.redis = redis;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public boolean isHealthy() {
        try {
            return "PONG".equals(redis.ping());
        } catch (Exception e) {
            // Jedis throws unchecked JedisConnectionException when Redis is unreachable or times out.
            log.warn("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }
}
