package io.github.tonreverse.payment.health;

import static io.github.tonreverse.payment.health.DatabaseHealthCheckTest.closedPort;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/** Failure path only, like {@link DatabaseHealthCheckTest}. */
class RedisHealthCheckTest {

    @Test
    void reportsUnhealthyInsteadOfThrowingWhenRedisIsUnreachable() throws IOException {
        try (RedisClient redis = RedisClient.create(URI.create("redis://localhost:" + closedPort()))) {
            RedisHealthCheck check = new RedisHealthCheck(redis);

            assertThat(check.name()).isEqualTo("redis");
            assertThat(check.isHealthy()).isFalse();
        }
    }
}
