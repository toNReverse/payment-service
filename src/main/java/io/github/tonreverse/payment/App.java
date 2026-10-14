package io.github.tonreverse.payment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.tonreverse.payment.api.HealthController;
import io.github.tonreverse.payment.config.AppConfig;
import io.github.tonreverse.payment.health.DatabaseHealthCheck;
import io.github.tonreverse.payment.health.RedisHealthCheck;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson3;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;

/**
 * Entry point and the only place where objects are created and connected (manual dependency injection).
 * Reading main() top to bottom shows the whole object graph.
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private App() {}

    public static void main(String[] args) {
        AppConfig config = AppConfig.from(System.getenv());
        log.info("Starting with {}", config);

        // Infrastructure: connection pools, created once and shared.
        HikariDataSource dataSource = createDataSource(config);
        RedisClient redis = RedisClient.create(URI.create(config.redisUrl()));

        // Application objects, each receiving its dependencies through the constructor.
        HealthController health = new HealthController(List.of(
                new DatabaseHealthCheck(dataSource),
                new RedisHealthCheck(redis)));

        Javalin server = createServer(health);

        // On SIGTERM (docker stop): stop taking requests first, then close the pools.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            redis.close();
            dataSource.close();
        }, "shutdown"));

        server.start(config.port());
    }

    /**
     * Builds the HTTP server from ready-made controllers, without starting it.
     *
     * <p>main() and the tests both call this, so tests exercise the same routes and JSON mapping as
     * production. Only the controllers' dependencies differ (fakes instead of Postgres and Redis).
     */
    public static Javalin createServer(HealthController health) {
        return Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.jsonMapper(new JavalinJackson3());

            config.routes.get("/health", health::handle);
        });
    }

    private static HikariDataSource createDataSource(AppConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("payment-db");
        hikari.setJdbcUrl(config.databaseUrl());
        hikari.setUsername(config.databaseUser());
        hikari.setPassword(config.databasePassword());
        // Wait at most 2 s for a connection (Hikari's default is 30 s), so /health answers quickly.
        hikari.setConnectionTimeout(2_000);
        // Start even if Postgres is not reachable yet: /health reports it instead of the process crashing.
        hikari.setInitializationFailTimeout(-1);
        return new HikariDataSource(hikari);
    }
}
