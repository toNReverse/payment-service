package io.github.tonreverse.payment;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noHealthChecks;
import static io.github.tonreverse.payment.api.TestServer.noProducts;
import static io.github.tonreverse.payment.api.TestServer.noWebhooks;
import static io.github.tonreverse.payment.testing.StripeSignatures.event;
import static io.github.tonreverse.payment.testing.StripeSignatures.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.api.OrderController;
import io.github.tonreverse.payment.api.StripeWebhookController;
import io.github.tonreverse.payment.api.TestServer;
import io.github.tonreverse.payment.payment.StripeWebhookParser;
import io.github.tonreverse.payment.persistence.JooqFraudFlagRepository;
import io.github.tonreverse.payment.persistence.JooqIdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.JooqOrderRepository;
import io.github.tonreverse.payment.persistence.JooqPaymentEventRepository;
import io.github.tonreverse.payment.persistence.JooqProductRepository;
import io.github.tonreverse.payment.persistence.JooqTransactions;
import io.github.tonreverse.payment.persistence.TestDatabase;
import io.github.tonreverse.payment.service.FraudService;
import io.github.tonreverse.payment.service.OrderService;
import io.github.tonreverse.payment.service.PaymentEventService;
import io.github.tonreverse.payment.testing.StubPaymentGateway;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Webhooks end to end: an order placed through POST /orders, then signed Stripe events sent to
 * POST /webhooks/stripe, checked through GET /orders/{id}. Real HTTP, service, repositories and Postgres.
 */
@Tag("integration")
class StripeWebhookIntegrationTest {

    private static final String SECRET = "whsec_test_end_to_end";

    private final DSLContext dsl = TestDatabase.dsl();
    private final JooqOrderRepository orders = new JooqOrderRepository(dsl);
    private final JooqTransactions transactions = new JooqTransactions(dsl);
    private final OrderService orderService = new OrderService(new JooqProductRepository(dsl), orders,
            new JooqIdempotencyKeyRepository(dsl), new JooqFraudFlagRepository(dsl), transactions,
            new StubPaymentGateway(), FraudService.none(), Clock.systemUTC(), UUID::randomUUID,
            OrderService.DEFAULT_LOCK_TIMEOUT);
    private final PaymentEventService paymentEvents = new PaymentEventService(orders, new JooqPaymentEventRepository(dsl),
            new JooqFraudFlagRepository(dsl), transactions, Clock.systemUTC(), UUID::randomUUID);
    private final TestServer server = TestServer.start(noHealthChecks(), noProducts(), new OrderController(orderService),
            new StripeWebhookController(new StripeWebhookParser(SECRET, Clock.systemUTC()), paymentEvents));

    private String orderId;
    private String sessionId;

    @BeforeEach
    void placeAnOrder() throws Exception {
        TestDatabase.deleteTestData();
        HttpResponse<String> created = server.post("/orders",
                "{\"customerEmail\": \"anna@example.com\", \"items\": [{\"productId\": \"pandoro\", \"quantity\": 2}]}",
                "Idempotency-Key", "test-key-" + UUID.randomUUID());
        orderId = json(created).path("id").stringValue();
        sessionId = "cs_test_" + orderId.replace("-", ""); // how StubPaymentGateway names sessions
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private String session(String eventId, String type, String paymentStatus, long amountTotal) {
        return event(eventId, type, """
                {"id": "%s", "object": "checkout.session", "client_reference_id": "%s", "metadata": {"order_id": "%s"},
                 "payment_status": "%s", "amount_total": %d, "currency": "eur"}""".formatted(
                sessionId, orderId, orderId, paymentStatus, amountTotal));
    }

    private String refund(String eventId) {
        return event(eventId, "charge.refunded", """
                {"id": "ch_test_1", "object": "charge", "metadata": {"order_id": "%s"},
                 "refunded": true, "amount_refunded": 5200, "currency": "eur"}""".formatted(orderId));
    }

    private HttpResponse<String> deliver(String payload) throws Exception {
        return server.post("/webhooks/stripe", payload, "Stripe-Signature", sign(payload, SECRET, Instant.now()));
    }

    private JsonNode currentOrder() throws Exception {
        return json(server.get("/orders/" + orderId));
    }

    @Test
    void aPaidCheckoutMarksTheOrderPaid() throws Exception {
        HttpResponse<String> response = deliver(session("evt_1", "checkout.session.completed", "paid", 5_200));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("result").stringValue()).isEqualTo("APPLIED");
        assertThat(currentOrder().path("status").stringValue()).isEqualTo("PAID");
        assertThat(currentOrder().path("checkoutUrl").isNull()).isTrue();
    }

    @Test
    void aForgedRequestIsRejectedAndChangesNothing() throws Exception {
        String payload = session("evt_1", "checkout.session.completed", "paid", 5_200);
        HttpResponse<String> forged =
                server.post("/webhooks/stripe", payload, "Stripe-Signature", sign(payload, "whsec_guessed", Instant.now()));

        assertThat(forged.statusCode()).isEqualTo(400);
        assertThat(json(forged).path("error").stringValue()).isEqualTo("invalid_webhook");
        assertThat(server.post("/webhooks/stripe", payload).statusCode()).isEqualTo(400);
        assertThat(currentOrder().path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void aRedeliveredEventIsAcknowledgedAsADuplicate() throws Exception {
        String payload = session("evt_1", "checkout.session.completed", "paid", 5_200);
        deliver(payload);

        HttpResponse<String> again = deliver(payload);

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json(again).path("result").stringValue()).isEqualTo("DUPLICATE");
    }

    @Test
    void aRefundBeforeThePaymentGets409SoStripeSendsItAgainLater() throws Exception {
        HttpResponse<String> early = deliver(refund("evt_refund"));

        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(json(early).path("error").stringValue()).isEqualTo("event_out_of_order");

        deliver(session("evt_paid", "checkout.session.completed", "paid", 5_200));
        assertThat(deliver(refund("evt_refund")).statusCode()).isEqualTo(200);
        assertThat(currentOrder().path("status").stringValue()).isEqualTo("REFUNDED");
    }

    @Test
    void anExpiredCheckoutExpiresTheOrder() throws Exception {
        deliver(session("evt_1", "checkout.session.expired", "unpaid", 5_200));

        assertThat(currentOrder().path("status").stringValue()).isEqualTo("EXPIRED");
    }

    @Test
    void aPaymentOfTheWrongAmountFlagsTheOrder() throws Exception {
        HttpResponse<String> response = deliver(session("evt_1", "checkout.session.completed", "paid", 100));

        assertThat(json(response).path("result").stringValue()).isEqualTo("FLAGGED");
        assertThat(currentOrder().path("status").stringValue()).isEqualTo("FLAGGED");
    }

    @Test
    void withoutASigningSecretTheEndpointIsUnavailable() throws Exception {
        try (TestServer unconfigured = TestServer.start(noHealthChecks(), noProducts(), TestServer.noOrders(), noWebhooks())) {
            String payload = session("evt_1", "checkout.session.completed", "paid", 5_200);

            HttpResponse<String> response = unconfigured.post("/webhooks/stripe", payload,
                    "Stripe-Signature", sign(payload, SECRET, Instant.now()));

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json(response).path("error").stringValue()).isEqualTo("webhooks_not_configured");
        }
    }
}
