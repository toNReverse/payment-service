package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.App;
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
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real HTTP server (same routes, JSON mapper and error handling as production) on a random free
 * port, built from whatever controllers a test passes in. Use with try-with-resources so it always stops.
 * Public because the full-stack integration test in the root package uses it too.
 */
public final class TestServer implements AutoCloseable {

    private final Javalin server;
    private final HttpClient http = HttpClient.newHttpClient();

    private TestServer(Javalin server) {
        this.server = server;
    }

    public static TestServer start(HealthController health, ProductController products, OrderController orders) {
        return start(health, products, orders, noWebhooks());
    }

    public static TestServer start(HealthController health, ProductController products, OrderController orders,
            StripeWebhookController webhooks) {
        return start(health, products, orders, webhooks, noRateLimit());
    }

    public static TestServer start(HealthController health, ProductController products, OrderController orders,
            StripeWebhookController webhooks, OrderRateLimitFilter orderRateLimit) {
        return new TestServer(App.createServer(health, products, orders, webhooks, orderRateLimit).start(0));
    }

    /** For tests that do not care about rate limiting: a limit no test reaches. */
    public static OrderRateLimitFilter noRateLimit() {
        return new OrderRateLimitFilter(new FixedWindowRateLimiter("orders",
                new InMemoryWindowCounter(Clock.systemUTC()), Integer.MAX_VALUE, Duration.ofMinutes(1), Clock.systemUTC()));
    }

    /** For tests that do not care about health checks. */
    public static HealthController noHealthChecks() {
        return new HealthController(List.of());
    }

    /** For tests that do not care about the catalog. */
    public static ProductController noProducts() {
        return new ProductController(new InMemoryProductRepository());
    }

    /** For tests that do not care about orders. */
    public static OrderController noOrders() {
        return new OrderController(new OrderService(new InMemoryProductRepository(), new InMemoryOrderRepository(),
                new InMemoryIdempotencyKeyRepository(), new InMemoryFraudFlagRepository(), new DirectTransactions(),
                new StubPaymentGateway(), FraudService.none(), Clock.systemUTC(), UUID::randomUUID,
                OrderService.DEFAULT_LOCK_TIMEOUT));
    }

    /** For tests that do not send webhooks. */
    public static StripeWebhookController noWebhooks() {
        return StripeWebhookController.notConfigured();
    }

    public HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET().build());
    }

    /** @param headers name/value pairs, e.g. "Idempotency-Key", "abc" */
    public HttpResponse<String> post(String path, String jsonBody, String... headers) throws Exception {
        HttpRequest.Builder request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return send(request.build());
    }

    public static JsonNode json(HttpResponse<String> response) {
        return JsonMapper.shared().readTree(response.body());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        server.stop();
    }
}
