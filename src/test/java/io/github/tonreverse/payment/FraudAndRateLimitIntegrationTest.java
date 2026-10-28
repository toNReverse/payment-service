package io.github.tonreverse.payment;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noHealthChecks;
import static io.github.tonreverse.payment.api.TestServer.noProducts;
import static io.github.tonreverse.payment.api.TestServer.noWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.api.OrderController;
import io.github.tonreverse.payment.api.OrderRateLimitFilter;
import io.github.tonreverse.payment.api.TestServer;
import io.github.tonreverse.payment.domain.FraudFlag;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.fraud.OrderTotalRule;
import io.github.tonreverse.payment.fraud.OrdersPerEmailRule;
import io.github.tonreverse.payment.fraud.QuantityPerProductRule;
import io.github.tonreverse.payment.persistence.JooqFraudFlagRepository;
import io.github.tonreverse.payment.persistence.JooqIdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.JooqOrderRepository;
import io.github.tonreverse.payment.persistence.JooqProductRepository;
import io.github.tonreverse.payment.persistence.JooqTransactions;
import io.github.tonreverse.payment.persistence.TestDatabase;
import io.github.tonreverse.payment.ratelimit.FixedWindowRateLimiter;
import io.github.tonreverse.payment.ratelimit.RedisWindowCounter;
import io.github.tonreverse.payment.ratelimit.WindowCounter;
import io.github.tonreverse.payment.service.FraudService;
import io.github.tonreverse.payment.service.OrderService;
import io.github.tonreverse.payment.testing.StubPaymentGateway;
import io.github.tonreverse.payment.testing.TestClock;
import io.github.tonreverse.payment.testing.TestRedis;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/**
 * Fraud rules and rate limiting end to end: HTTP, OrderService, Postgres for orders and fraud flags, and
 * Redis for the counters. The clock is fixed, so no test can straddle a window boundary.
 */
@Tag("integration")
class FraudAndRateLimitIntegrationTest {

    private final DSLContext dsl = TestDatabase.dsl();
    private final JooqOrderRepository orders = new JooqOrderRepository(dsl);
    private final JooqFraudFlagRepository fraudFlags = new JooqFraudFlagRepository(dsl);
    private final StubPaymentGateway gateway = new StubPaymentGateway();
    private final TestClock clock = new TestClock(Instant.parse("2026-12-01T10:15:30Z"));
    private TestServer server;

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
        TestRedis.flush();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /** Low limits: total 50.00 EUR, 10 per product, 3 orders per email per hour, 5 requests per IP per minute. */
    private TestServer startWith(WindowCounter counter) {
        FraudService fraud = new FraudService(List.of(
                new OrderTotalRule(Money.of(5_000, "EUR")),
                new QuantityPerProductRule(10),
                new OrdersPerEmailRule(counter, 3, Duration.ofHours(1))));
        OrderService service = new OrderService(new JooqProductRepository(dsl), orders, new JooqIdempotencyKeyRepository(dsl),
                fraudFlags, new JooqTransactions(dsl), gateway, fraud, clock, UUID::randomUUID, OrderService.DEFAULT_LOCK_TIMEOUT);
        OrderRateLimitFilter rateLimit = new OrderRateLimitFilter(
                new FixedWindowRateLimiter("orders", counter, 5, Duration.ofMinutes(1), clock));
        server = TestServer.start(noHealthChecks(), noProducts(), new OrderController(service), noWebhooks(), rateLimit);
        return server;
    }

    private TestServer startWithRedis() {
        return startWith(new RedisWindowCounter(TestRedis.client(), clock));
    }

    private HttpResponse<String> order(TestServer server, String email, int quantity) throws Exception {
        return server.post("/orders", """
                {"customerEmail": "%s", "items": [{"productId": "pandoro", "quantity": %d}]}
                """.formatted(email, quantity), "Idempotency-Key", "test-key-" + UUID.randomUUID());
    }

    private List<FraudFlag> flagsOf(HttpResponse<String> response) {
        return fraudFlags.findByOrderId(UUID.fromString(json(response).path("id").stringValue()));
    }

    @Test
    void theFourthOrderFromOneEmailWithinTheHourIsFlagged() throws Exception {
        TestServer server = startWithRedis();
        for (int i = 0; i < 3; i++) {
            assertThat(json(order(server, "anna@example.com", 1)).path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
        }

        HttpResponse<String> fourth = order(server, "anna@example.com", 1);

        assertThat(fourth.statusCode()).isEqualTo(201);
        assertThat(json(fourth).path("status").stringValue()).isEqualTo("FLAGGED");
        assertThat(flagsOf(fourth)).extracting(FraudFlag::rule).containsExactly("orders_per_email_above_limit");
        assertThat(gateway.calls()).isEqualTo(3);
    }

    @Test
    void anOrderAboveTheLimitsIsStoredFlaggedWithEveryReason() throws Exception {
        TestServer server = startWithRedis();

        // 12 pandori: 312.00 EUR, above both the total and the per-product quantity limit.
        HttpResponse<String> response = order(server, "bruno@example.com", 12);

        assertThat(json(response).path("status").stringValue()).isEqualTo("FLAGGED");
        assertThat(flagsOf(response)).extracting(FraudFlag::rule)
                .containsExactlyInAnyOrder("order_total_above_limit", "quantity_above_limit");
        assertThat(json(server.get("/orders/" + json(response).path("id").stringValue())).path("status").stringValue())
                .isEqualTo("FLAGGED");
        assertThat(gateway.calls()).isZero();
    }

    @Test
    void theSixthOrderFromOneAddressWithinTheMinuteIsRefused() throws Exception {
        TestServer server = startWithRedis();
        for (int i = 0; i < 5; i++) {
            assertThat(order(server, "customer" + i + "@example.com", 1).statusCode()).isEqualTo(201);
        }

        HttpResponse<String> sixth = order(server, "customer6@example.com", 1);

        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(sixth.headers().firstValue("Retry-After")).hasValue("30");
    }

    @Test
    void ordersStillGoThroughWhenRedisIsUnreachable() throws Exception {
        // A real Jedis client pointed at a port where nothing listens: the same exceptions as a Redis outage.
        RedisClient deadRedis = RedisClient.create(URI.create("redis://localhost:" + closedPort()));
        TestServer server = startWith(new RedisWindowCounter(deadRedis, clock));

        for (int i = 0; i < 7; i++) {
            HttpResponse<String> response = order(server, "anna@example.com", 1);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(json(response).path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
        }
        deadRedis.close();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
