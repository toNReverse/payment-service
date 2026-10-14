package io.github.tonreverse.payment;

import com.zaxxer.hikari.HikariDataSource;
import io.github.tonreverse.payment.api.ApiErrors;
import io.github.tonreverse.payment.api.HealthController;
import io.github.tonreverse.payment.api.ProductController;
import io.github.tonreverse.payment.config.AppConfig;
import io.github.tonreverse.payment.health.DatabaseHealthCheck;
import io.github.tonreverse.payment.health.RedisHealthCheck;
import io.github.tonreverse.payment.persistence.Database;
import io.github.tonreverse.payment.persistence.JooqProductRepository;
import io.github.tonreverse.payment.persistence.ProductRepository;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson3;
import java.net.URI;
import java.util.List;
import org.jooq.DSLContext;
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
        HikariDataSource dataSource =
                Database.createDataSource(config.databaseUrl(), config.databaseUser(), config.databasePassword());
        // Bring the schema up to date before serving any request (see docs/decisions.md).
        Database.migrate(dataSource);
        DSLContext dsl = Database.createDsl(dataSource);
        RedisClient redis = RedisClient.create(URI.create(config.redisUrl()));

        // Repositories.
        ProductRepository productRepository = new JooqProductRepository(dsl);

        // HTTP controllers, each receiving its dependencies through the constructor.
        HealthController health = new HealthController(List.of(
                new DatabaseHealthCheck(dataSource),
                new RedisHealthCheck(redis)));
        ProductController products = new ProductController(productRepository);

        Javalin server = createServer(health, products);

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
    public static Javalin createServer(HealthController health, ProductController products) {
        return Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.jsonMapper(new JavalinJackson3());

            ApiErrors.register(config.routes);

            config.routes.get("/health", health::handle);
            config.routes.get("/products", products::list);
        });
    }
}
