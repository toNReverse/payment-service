package io.github.tonreverse.payment.api;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noHealthChecks;
import static io.github.tonreverse.payment.api.TestServer.noProducts;
import static io.github.tonreverse.payment.api.TestServer.noWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.ratelimit.FixedWindowRateLimiter;
import io.github.tonreverse.payment.service.FraudService;
import io.github.tonreverse.payment.service.OrderService;
import io.github.tonreverse.payment.testing.DirectTransactions;
import io.github.tonreverse.payment.testing.InMemoryFraudFlagRepository;
import io.github.tonreverse.payment.testing.InMemoryIdempotencyKeyRepository;
import io.github.tonreverse.payment.testing.InMemoryOrderRepository;
import io.github.tonreverse.payment.testing.InMemoryProductRepository;
import io.github.tonreverse.payment.testing.InMemoryWindowCounter;
import io.github.tonreverse.payment.testing.StubPaymentGateway;
import io.github.tonreverse.payment.testing.TestClock;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** POST /orders is limited per client IP; every test request comes from localhost, so they share a limit. */
class OrderRateLimitTest {

    private static final String ORDER = """
            {"customerEmail": "anna@example.com", "items": [{"productId": "pandoro", "quantity": 1}]}
            """;

    private final TestClock clock = new TestClock(Instant.parse("2026-12-01T10:15:30Z"));
    private final OrderService service = new OrderService(
            new InMemoryProductRepository(new Product("pandoro", "Pandoro 1 kg", Money.of(2_600, "EUR"), true)),
            new InMemoryOrderRepository(), new InMemoryIdempotencyKeyRepository(), new InMemoryFraudFlagRepository(),
            new DirectTransactions(), new StubPaymentGateway(), FraudService.none(), clock, UUID::randomUUID,
            OrderService.DEFAULT_LOCK_TIMEOUT);
    private final TestServer server = TestServer.start(noHealthChecks(), noProducts(), new OrderController(service),
            noWebhooks(), new OrderRateLimitFilter(new FixedWindowRateLimiter(
                    "orders", new InMemoryWindowCounter(clock), 2, Duration.ofMinutes(1), clock)));

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpResponse<String> postOrder(String body) throws Exception {
        return server.post("/orders", body, "Idempotency-Key", "key-" + UUID.randomUUID());
    }

    @Test
    void refusesOrdersBeyondTheLimitWith429AndRetryAfter() throws Exception {
        assertThat(postOrder(ORDER).statusCode()).isEqualTo(201);
        assertThat(postOrder(ORDER).statusCode()).isEqualTo(201);

        HttpResponse<String> third = postOrder(ORDER);

        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Retry-After")).hasValue("30");
        assertThat(json(third).path("error").stringValue()).isEqualTo("rate_limited");
    }

    @Test
    void invalidRequestsCountTooBecauseTheLimitIsCheckedFirst() throws Exception {
        // Otherwise an attacker could probe validation as fast as they like.
        postOrder("{}");
        postOrder("{}");

        assertThat(postOrder(ORDER).statusCode()).isEqualTo(429);
    }

    @Test
    void readingOrdersIsNotLimited() throws Exception {
        String id = json(postOrder(ORDER)).path("id").stringValue();
        postOrder(ORDER);
        postOrder(ORDER);

        assertThat(server.get("/orders/" + id).statusCode()).isEqualTo(200);
    }
}
