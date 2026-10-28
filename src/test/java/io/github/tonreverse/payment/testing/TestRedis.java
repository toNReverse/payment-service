package io.github.tonreverse.payment.testing;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.RedisClient;

/** One real Redis for the whole test run, same image as compose.yaml. Started on first use. */
public final class TestRedis {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
    private static RedisClient client;

    private TestRedis() {}

    public static synchronized RedisClient client() {
        if (client == null) {
            REDIS.start();
            client = RedisClient.create(URI.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)));
        }
        return client;
    }

    /** Empties Redis between tests. */
    public static void flush() {
        client().flushAll();
    }
}
