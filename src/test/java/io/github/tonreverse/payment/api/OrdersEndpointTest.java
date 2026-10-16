package io.github.tonreverse.payment.api;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noHealthChecks;
import static io.github.tonreverse.payment.api.TestServer.noProducts;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.payment.PaymentGatewayException;
import io.github.tonreverse.payment.service.CreateOrderCommand;
import io.github.tonreverse.payment.service.OrderService;
import io.github.tonreverse.payment.testing.DirectTransactions;
import io.github.tonreverse.payment.testing.InMemoryIdempotencyKeyRepository;
import io.github.tonreverse.payment.testing.InMemoryOrderRepository;
import io.github.tonreverse.payment.testing.InMemoryProductRepository;
import io.github.tonreverse.payment.testing.StubPaymentGateway;
import io.github.tonreverse.payment.testing.TestClock;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The HTTP contract of POST /orders and GET /orders/{id}, with in-memory fakes behind the real service.
 * OrderIdempotencyIntegrationTest repeats the idempotency scenarios against Postgres.
 */
class OrdersEndpointTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30.123456Z");
    private static final String KEY = "6f1c0a52-retry-safe";
    private static final String ORDER = """
            {"customerEmail": "anna@example.com",
             "items": [{"productId": "pandoro", "quantity": 2}, {"productId": "panettone-classico", "quantity": 1}]}
            """;

    private final InMemoryOrderRepository orders = new InMemoryOrderRepository();
    private final InMemoryIdempotencyKeyRepository keys = new InMemoryIdempotencyKeyRepository();
    private final StubPaymentGateway gateway = new StubPaymentGateway();
    private final OrderService service = new OrderService(
            new InMemoryProductRepository(
                    new Product("pandoro", "Pandoro 1 kg", Money.of(2_600, "EUR"), true),
                    new Product("panettone-classico", "Panettone Classico 1 kg", Money.of(2_800, "EUR"), true)),
            orders, keys, new DirectTransactions(), gateway, new TestClock(NOW), UUID::randomUUID,
            OrderService.DEFAULT_LOCK_TIMEOUT);
    private final TestServer server = TestServer.start(noHealthChecks(), noProducts(), new OrderController(service));

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpResponse<String> postOrder(String body) throws Exception {
        return server.post("/orders", body, "Idempotency-Key", KEY);
    }

    @Nested
    class Creating {

        @Test
        void returns201WithTheOrderAndItsCheckoutUrl() throws Exception {
            HttpResponse<String> response = postOrder(ORDER);

            assertThat(response.statusCode()).isEqualTo(201);
            JsonNode body = json(response);
            String id = body.path("id").stringValue();
            assertThat(UUID.fromString(id)).isNotNull();
            assertThat(response.headers().firstValue("Location")).hasValue("/orders/" + id);
            assertThat(response.headers().firstValue("Idempotent-Replayed")).isEmpty();
            assertThat(body.path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
            assertThat(body.path("customerEmail").stringValue()).isEqualTo("anna@example.com");
            assertThat(body.path("total").path("amountMinor").longValue()).isEqualTo(8_000);
            assertThat(body.path("total").path("currency").stringValue()).isEqualTo("EUR");
            JsonNode firstItem = body.path("items").get(0);
            assertThat(firstItem.path("productId").stringValue()).isEqualTo("pandoro");
            assertThat(firstItem.path("quantity").intValue()).isEqualTo(2);
            assertThat(firstItem.path("unitPrice").path("amountMinor").longValue()).isEqualTo(2_600);
            assertThat(firstItem.path("lineTotal").path("amountMinor").longValue()).isEqualTo(5_200);
            assertThat(body.path("checkoutUrl").stringValue()).startsWith("https://checkout.test.invalid/pay/cs_test_");
            assertThat(Instant.parse(body.path("createdAt").stringValue())).isEqualTo(NOW);
        }

        @Test
        void normalizesTheEmailAddress() throws Exception {
            HttpResponse<String> response = postOrder("""
                    {"customerEmail": "  Anna@Example.COM ", "items": [{"productId": "pandoro", "quantity": 1}]}
                    """);

            assertThat(json(response).path("customerEmail").stringValue()).isEqualTo("anna@example.com");
        }

        @Test
        void aProductThatIsNotForSaleIs400() throws Exception {
            HttpResponse<String> response = postOrder("""
                    {"customerEmail": "anna@example.com", "items": [{"productId": "panettone-mango", "quantity": 1}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(details(response)).containsExactly("product 'panettone-mango' is not for sale");
        }
    }

    @Nested
    class Idempotency {

        @Test
        void repeatingTheRequestReturnsTheSameOrderAndMarksItAsReplayed() throws Exception {
            String firstId = json(postOrder(ORDER)).path("id").stringValue();

            HttpResponse<String> repeat = postOrder(ORDER);

            assertThat(repeat.statusCode()).isEqualTo(201);
            assertThat(json(repeat).path("id").stringValue()).isEqualTo(firstId);
            assertThat(repeat.headers().firstValue("Idempotent-Replayed")).hasValue("true");
            assertThat(gateway.calls()).isEqualTo(1);
            assertThat(orders.count()).isEqualTo(1);
        }

        @Test
        void reusingTheKeyForADifferentOrderIs422() throws Exception {
            postOrder(ORDER);

            HttpResponse<String> response = postOrder("""
                    {"customerEmail": "anna@example.com", "items": [{"productId": "pandoro", "quantity": 9}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(json(response).path("error").stringValue()).isEqualTo("idempotency_key_reused");
            assertThat(orders.count()).isEqualTo(1);
        }

        @Test
        void aKeyWhoseFirstRequestIsStillRunningIs409WithRetryAfter() throws Exception {
            String fingerprint = new CreateOrderCommand("anna@example.com", List.of(
                    new CreateOrderCommand.Line("pandoro", 2),
                    new CreateOrderCommand.Line("panettone-classico", 1))).fingerprint();
            keys.tryReserve(KEY, fingerprint, NOW);

            HttpResponse<String> response = postOrder(ORDER);

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
            assertThat(json(response).path("error").stringValue()).isEqualTo("idempotency_key_in_use");
        }

        @Test
        void aPaymentProviderOutageIs502AndTheSameKeyWorksAfterwards() throws Exception {
            gateway.failWith(new PaymentGatewayException("connect timed out: api.stripe.com"));

            HttpResponse<String> failed = postOrder(ORDER);

            assertThat(failed.statusCode()).isEqualTo(502);
            assertThat(json(failed).path("error").stringValue()).isEqualTo("payment_provider_unavailable");
            assertThat(failed.body()).doesNotContain("api.stripe.com");

            gateway.succeed();
            assertThat(postOrder(ORDER).statusCode()).isEqualTo(201);
        }

        @Test
        void theIdempotencyKeyHeaderIsRequired() throws Exception {
            HttpResponse<String> response = server.post("/orders", ORDER);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(details(response)).containsExactly("Idempotency-Key header is required");
        }

        @Test
        void anOverlongIdempotencyKeyIs400() throws Exception {
            HttpResponse<String> response = server.post("/orders", ORDER, "Idempotency-Key", "k".repeat(256));

            assertThat(response.statusCode()).isEqualTo(400);
        }
    }

    @Nested
    class Validation {

        @Test
        void reportsEveryInvalidFieldAtOnce() throws Exception {
            HttpResponse<String> response = postOrder("""
                    {"customerEmail": "not-an-email",
                     "items": [{"productId": "Pandoro!", "quantity": 0}, {"quantity": 1}]}
                    """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("error").stringValue()).isEqualTo("validation_failed");
            assertThat(details(response)).containsExactly(
                    "customerEmail must be a valid email address",
                    "items[0].productId must be 1-64 lowercase letters, digits or dashes",
                    "items[0].quantity must be between 1 and 1000",
                    "items[1].productId is required");
        }

        @Test
        void anOrderNeedsItems() throws Exception {
            HttpResponse<String> response = postOrder("""
                    {"customerEmail": "anna@example.com", "items": []}
                    """);

            assertThat(details(response)).containsExactly("items must contain at least one item");
        }

        @Test
        void aProductCanOnlyBeListedOnce() throws Exception {
            HttpResponse<String> response = postOrder("""
                    {"customerEmail": "anna@example.com",
                     "items": [{"productId": "pandoro", "quantity": 1}, {"productId": "pandoro", "quantity": 2}]}
                    """);

            assertThat(details(response)).containsExactly("items[1].productId 'pandoro' is listed more than once");
        }

        @Test
        void malformedJsonIs400() throws Exception {
            HttpResponse<String> response = postOrder("{\"customerEmail\": ");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(details(response)).containsExactly("request body must be valid JSON");
        }

        @Test
        void unknownFieldsAreRejectedRatherThanIgnored() throws Exception {
            HttpResponse<String> response = postOrder("""
                    {"customerEmail": "anna@example.com", "items": [{"productId": "pandoro", "quantity": 1}],
                     "discountPercent": 50}
                    """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(details(response)).containsExactly("unknown field 'discountPercent'");
        }

        @Test
        void quantitiesMustBeWholeJsonNumbers() throws Exception {
            for (String quantity : List.of("2.5", "\"2\"")) {
                HttpResponse<String> response = postOrder("""
                        {"customerEmail": "anna@example.com", "items": [{"productId": "pandoro", "quantity": %s}]}
                        """.formatted(quantity));

                assertThat(response.statusCode()).as("quantity %s", quantity).isEqualTo(400);
                assertThat(details(response)).containsExactly("items[0].quantity has the wrong type");
            }
        }
    }

    @Nested
    class Reading {

        @Test
        void returnsASavedOrder() throws Exception {
            String id = json(postOrder(ORDER)).path("id").stringValue();

            HttpResponse<String> response = server.get("/orders/" + id);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("id").stringValue()).isEqualTo(id);
            assertThat(json(response).path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
        }

        @Test
        void anUnknownOrderIs404() throws Exception {
            HttpResponse<String> response = server.get("/orders/" + UUID.randomUUID());

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(json(response).path("error").stringValue()).isEqualTo("not_found");
        }

        @Test
        void anIdThatIsNotAUuidIs400() throws Exception {
            HttpResponse<String> response = server.get("/orders/42");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(details(response)).containsExactly("order id must be a UUID");
        }
    }

    private static List<String> details(HttpResponse<String> response) {
        return json(response).path("details").valueStream().map(JsonNode::stringValue).toList();
    }
}
