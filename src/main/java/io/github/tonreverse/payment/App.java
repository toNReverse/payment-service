package io.github.tonreverse.payment;

import com.zaxxer.hikari.HikariDataSource;
import io.github.tonreverse.payment.api.ApiErrors;
import io.github.tonreverse.payment.api.HealthController;
import io.github.tonreverse.payment.api.OrderController;
import io.github.tonreverse.payment.api.OrderRateLimitFilter;
import io.github.tonreverse.payment.api.ProductController;
import io.github.tonreverse.payment.api.StripeWebhookController;
import io.github.tonreverse.payment.config.AppConfig;
import io.github.tonreverse.payment.health.DatabaseHealthCheck;
import io.github.tonreverse.payment.health.RedisHealthCheck;
import io.github.tonreverse.payment.payment.FakePaymentGateway;
import io.github.tonreverse.payment.payment.PaymentGateway;
import io.github.tonreverse.payment.payment.StripePaymentGateway;
import io.github.tonreverse.payment.payment.StripeWebhookParser;
import io.github.tonreverse.payment.persistence.Database;
import io.github.tonreverse.payment.persistence.FraudFlagRepository;
import io.github.tonreverse.payment.persistence.IdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.JooqFraudFlagRepository;
import io.github.tonreverse.payment.persistence.JooqIdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.JooqOrderRepository;
import io.github.tonreverse.payment.persistence.JooqPaymentEventRepository;
import io.github.tonreverse.payment.persistence.JooqProductRepository;
import io.github.tonreverse.payment.persistence.JooqTransactions;
import io.github.tonreverse.payment.persistence.OrderRepository;
import io.github.tonreverse.payment.persistence.PaymentEventRepository;
import io.github.tonreverse.payment.persistence.ProductRepository;
import io.github.tonreverse.payment.persistence.Transactions;
import io.github.tonreverse.payment.ratelimit.FixedWindowRateLimiter;
import io.github.tonreverse.payment.ratelimit.RedisWindowCounter;
import io.github.tonreverse.payment.service.FraudService;
import io.github.tonreverse.payment.service.OrderService;
import io.github.tonreverse.payment.service.PaymentEventService;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson3;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
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
        PaymentEventRepository paymentEventRepository = new JooqPaymentEventRepository(dsl);
        FraudFlagRepository fraudFlagRepository = new JooqFraudFlagRepository(dsl);
        Transactions transactions = new JooqTransactions(dsl);

        // Payment provider.
        PaymentGateway paymentGateway = paymentGateway(config);

        // Services.
        OrderService orderService = new OrderService(productRepository, orderRepository, idempotencyKeys,
                fraudFlagRepository, transactions, paymentGateway, FraudService.none(), Clock.systemUTC(),
                UUID::randomUUID, OrderService.DEFAULT_LOCK_TIMEOUT);
        PaymentEventService paymentEvents = new PaymentEventService(orderRepository, paymentEventRepository,
                fraudFlagRepository, transactions, Clock.systemUTC(), UUID::randomUUID);

        // HTTP controllers, each receiving its dependencies through the constructor.
        HealthController health = new HealthController(List.of(
                new DatabaseHealthCheck(dataSource),
                new RedisHealthCheck(redis)));
        ProductController products = new ProductController(productRepository);
        OrderController orders = new OrderController(orderService);
        StripeWebhookController webhooks = webhookController(config, paymentEvents);

        OrderRateLimitFilter orderRateLimit = new OrderRateLimitFilter(new FixedWindowRateLimiter(
                "orders", new RedisWindowCounter(redis, Clock.systemUTC()), 10, Duration.ofMinutes(1), Clock.systemUTC()));

        Javalin server = createServer(health, products, orders, webhooks, orderRateLimit);

        // On SIGTERM (docker stop): stop taking requests first, then close the pools.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            redis.close();
            dataSource.close();
        }, "shutdown"));

        server.start(config.port());
    }

    /**
     * Stripe when a test-mode key is configured, otherwise a fake. The fake lets the service, CI and
     * docker compose run without any Stripe account; AppConfig has already refused live keys.
     */
    private static PaymentGateway paymentGateway(AppConfig config) {
        if (config.usesStripe()) {
            log.info("Payments: Stripe Checkout, test mode");
            return new StripePaymentGateway(StripePaymentGateway.client(config.stripeSecretKey()),
                    config.checkoutSuccessUrl(), config.checkoutCancelUrl());
        }
        log.warn("STRIPE_SECRET_KEY is not set: using FakePaymentGateway, checkout URLs are placeholders");
        return new FakePaymentGateway();
    }

    /** Without a signing secret, events cannot be verified, so the endpoint refuses them all (503). */
    private static StripeWebhookController webhookController(AppConfig config, PaymentEventService paymentEvents) {
        if (config.receivesStripeWebhooks()) {
            return new StripeWebhookController(
                    new StripeWebhookParser(config.stripeWebhookSecret(), Clock.systemUTC()), paymentEvents);
        }
        log.warn("STRIPE_WEBHOOK_SECRET is not set: POST /webhooks/stripe will answer 503");
        return StripeWebhookController.notConfigured();
    }

    /**
     * Builds the HTTP server from ready-made controllers, without starting it.
     *
     * <p>main() and the tests both call this, so tests exercise the same routes, JSON mapping and error
     * handling as production. Only the controllers' dependencies differ (fakes instead of Postgres).
     */
    public static Javalin createServer(HealthController health, ProductController products, OrderController orders,
            StripeWebhookController webhooks, OrderRateLimitFilter orderRateLimit) {
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

            // Before-handlers run ahead of the endpoint: a refused request never reaches OrderController.
            config.routes.before("/orders", orderRateLimit::handle);

            config.routes.get("/health", health::handle);
            config.routes.get("/products", products::list);
            config.routes.post("/orders", orders::create);
            config.routes.get("/orders/{id}", orders::get);
            config.routes.post("/webhooks/stripe", webhooks::handle);
        });
    }
}
