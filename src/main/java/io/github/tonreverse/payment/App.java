package io.github.tonreverse.payment;

import com.zaxxer.hikari.HikariDataSource;
import io.github.tonreverse.payment.api.ApiErrors;
import io.github.tonreverse.payment.api.HealthController;
import io.github.tonreverse.payment.api.OrderController;
import io.github.tonreverse.payment.api.ProductController;
import io.github.tonreverse.payment.config.AppConfig;
import io.github.tonreverse.payment.health.DatabaseHealthCheck;
import io.github.tonreverse.payment.health.RedisHealthCheck;
import io.github.tonreverse.payment.payment.FakePaymentGateway;
import io.github.tonreverse.payment.payment.PaymentGateway;
import io.github.tonreverse.payment.persistence.Database;
import io.github.tonreverse.payment.persistence.IdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.JooqIdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.JooqOrderRepository;
import io.github.tonreverse.payment.persistence.JooqProductRepository;
import io.github.tonreverse.payment.persistence.JooqTransactions;
import io.github.tonreverse.payment.persistence.OrderRepository;
import io.github.tonreverse.payment.persistence.ProductRepository;
import io.github.tonreverse.payment.persistence.Transactions;
import io.github.tonreverse.payment.service.OrderService;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson3;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.RedisClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

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

        // Repositories and transactions.
        ProductRepository productRepository = new JooqProductRepository(dsl);
        OrderRepository orderRepository = new JooqOrderRepository(dsl);
        IdempotencyKeyRepository idempotencyKeys = new JooqIdempotencyKeyRepository(dsl);
        Transactions transactions = new JooqTransactions(dsl);

        // Payment provider. Replaced by the Stripe gateway in Milestone 5.
        PaymentGateway paymentGateway = new FakePaymentGateway();
        log.warn("Using FakePaymentGateway: checkout URLs are placeholders, no payment can be made");

        // Services.
        OrderService orderService = new OrderService(productRepository, orderRepository, idempotencyKeys,
                transactions, paymentGateway, Clock.systemUTC(), UUID::randomUUID, OrderService.DEFAULT_LOCK_TIMEOUT);

        // HTTP controllers, each receiving its dependencies through the constructor.
        HealthController health = new HealthController(List.of(
                new DatabaseHealthCheck(dataSource),
                new RedisHealthCheck(redis)));
        ProductController products = new ProductController(productRepository);
        OrderController orders = new OrderController(orderService);

        Javalin server = createServer(health, products, orders);

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
     * <p>main() and the tests both call this, so tests exercise the same routes, JSON mapping and error
     * handling as production. Only the controllers' dependencies differ (fakes instead of Postgres).
     */
    public static Javalin createServer(HealthController health, ProductController products, OrderController orders) {
        return Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            // Strict JSON input. Jackson 3 by default ignores unknown fields and accepts 2.5 or "2" where
            // an integer is expected; for a payments API a typo or a fractional quantity must be a 400.
            // Javalin's default mapper also writes dates as epoch numbers; ISO-8601 strings are clearer.
            config.jsonMapper(new JavalinJackson3().updateMapper(mapper -> mapper
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)));

            ApiErrors.register(config.routes);

            config.routes.get("/health", health::handle);
            config.routes.get("/products", products::list);
            config.routes.post("/orders", orders::create);
            config.routes.get("/orders/{id}", orders::get);
        });
    }
}
