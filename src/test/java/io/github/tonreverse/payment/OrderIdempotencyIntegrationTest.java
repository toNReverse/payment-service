package io.github.tonreverse.payment;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noHealthChecks;
import static io.github.tonreverse.payment.api.TestServer.noProducts;
import static io.github.tonreverse.payment.persistence.jooq.Tables.ORDERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.api.OrderController;
import io.github.tonreverse.payment.api.TestServer;
import io.github.tonreverse.payment.payment.PaymentGatewayException;
import io.github.tonreverse.payment.persistence.IdempotencyRecord.State;
import io.github.tonreverse.payment.persistence.JooqFraudFlagRepository;
import io.github.tonreverse.payment.persistence.JooqIdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.JooqOrderRepository;
import io.github.tonreverse.payment.persistence.JooqProductRepository;
import io.github.tonreverse.payment.persistence.JooqTransactions;
import io.github.tonreverse.payment.persistence.TestDatabase;
import io.github.tonreverse.payment.service.CreateOrderCommand;
import io.github.tonreverse.payment.service.FraudService;
import io.github.tonreverse.payment.service.IdempotencyKeyInUseException;
import io.github.tonreverse.payment.service.OrderService;
import io.github.tonreverse.payment.testing.StubPaymentGateway;
import io.github.tonreverse.payment.testing.TestClock;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Idempotency end to end: HTTP, OrderService, jOOQ repositories and a real Postgres. Only the payment
 * provider is a stub. Unlike the in-memory tests, row locks, unique constraints and rollbacks are real here.
 */
@Tag("integration")
class OrderIdempotencyIntegrationTest {

    private static final Instant START = Instant.parse("2026-12-01T10:15:30.123456Z");
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);
    private static final String ORDER = """
            {"customerEmail": "anna@example.com", "items": [{"productId": "pandoro", "quantity": 2}]}
            """;

    private final DSLContext dsl = TestDatabase.dsl();
    private final JooqIdempotencyKeyRepository keys = new JooqIdempotencyKeyRepository(dsl);
    private final StubPaymentGateway gateway = new StubPaymentGateway();
    // A clock that does not move: every reservation in a test gets the same, predictable timestamp.
    private final OrderService service = new OrderService(new JooqProductRepository(dsl), new JooqOrderRepository(dsl),
            keys, new JooqFraudFlagRepository(dsl), new JooqTransactions(dsl), gateway, FraudService.none(),
            new TestClock(START), UUID::randomUUID, LOCK_TIMEOUT);
    private final TestServer server = TestServer.start(noHealthChecks(), noProducts(), new OrderController(service));
    private final String key = "test-key-" + UUID.randomUUID();

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void repeatingARequestStoresOneOrderAndReturnsItEachTime() throws Exception {
        HttpResponse<String> first = postOrder(ORDER);
        HttpResponse<String> second = postOrder(ORDER);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(json(second).path("id").stringValue()).isEqualTo(json(first).path("id").stringValue());
        assertThat(orderRows()).isEqualTo(1);
        assertThat(gateway.calls()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneOrder() throws Exception {
        // A slow provider keeps the first request holding the key while the others arrive.
        gateway.duringCall(order -> sleep(Duration.ofMillis(300)));
        int clients = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> responses = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                responses.add(pool.submit(() -> {
                    start.await();
                    return postOrder(ORDER);
                }));
            }
            start.countDown();

            List<HttpResponse<String>> created = new ArrayList<>();
            for (Future<HttpResponse<String>> future : responses) {
                HttpResponse<String> response = future.get();
                // Each client gets either the order (first or replayed) or "still in progress, retry".
                assertThat(response.statusCode()).isIn(201, 409);
                if (response.statusCode() == 201) {
                    created.add(response);
                }
            }

            assertThat(created).isNotEmpty();
            assertThat(created).filteredOn(r -> r.headers().firstValue("Idempotent-Replayed").isEmpty()).hasSize(1);
            assertThat(created).extracting(r -> json(r).path("id").stringValue()).containsOnly(
                    json(created.getFirst()).path("id").stringValue());
        }
        assertThat(orderRows()).isEqualTo(1);
        assertThat(gateway.calls()).isEqualTo(1);
    }

    @Test
    void reusingTheKeyForADifferentOrderIs422AndChangesNothing() throws Exception {
        postOrder(ORDER);

        HttpResponse<String> response = postOrder("""
                {"customerEmail": "anna@example.com", "items": [{"productId": "pandoro", "quantity": 3}]}
                """);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(orderRows()).isEqualTo(1);
    }

    @Test
    void aProviderOutageLeavesNoTraceSoTheSameKeyCanBeRetried() throws Exception {
        gateway.failWith(new PaymentGatewayException("Stripe is unreachable"));

        assertThat(postOrder(ORDER).statusCode()).isEqualTo(502);
        assertThat(orderRows()).isZero();
        assertThat(keys.find(key)).isEmpty();

        gateway.succeed();
        HttpResponse<String> retry = postOrder(ORDER);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(orderRows()).isEqualTo(1);
    }

    @Test
    void aRequestThatLostItsReservationRollsBackItsOrder() {
        // While "Stripe" is slow, another request concludes the reservation was abandoned and takes it over.
        Instant takenOverAt = START.plus(LOCK_TIMEOUT).plusSeconds(1);
        gateway.duringCall(order -> keys.tryTakeOver(key, START, takenOverAt));
        CreateOrderCommand command = new CreateOrderCommand("anna@example.com", List.of(new CreateOrderCommand.Line("pandoro", 2)));

        assertThatThrownBy(() -> service.createOrder(key, command)).isInstanceOf(IdempotencyKeyInUseException.class);

        // Our order was inserted, then rolled back when completing the key failed: no second order for one key.
        assertThat(orderRows()).isZero();
        assertThat(keys.find(key)).hasValueSatisfying(record -> {
            assertThat(record.state()).isEqualTo(State.IN_PROGRESS);
            assertThat(record.lockedAt()).isEqualTo(takenOverAt);
        });
    }

    @Test
    void aReplayShowsTheOrderAsItIsNow() throws Exception {
        String id = json(postOrder(ORDER)).path("id").stringValue();
        // Stand-in for the payment webhook of Milestone 6.
        dsl.update(ORDERS).set(ORDERS.STATUS, "PAID").where(ORDERS.ID.eq(UUID.fromString(id))).execute();

        HttpResponse<String> replay = postOrder(ORDER);

        assertThat(json(replay).path("id").stringValue()).isEqualTo(id);
        assertThat(json(replay).path("status").stringValue()).isEqualTo("PAID");
        assertThat(json(replay).path("checkoutUrl").isNull()).isTrue();
    }

    private HttpResponse<String> postOrder(String body) throws Exception {
        return server.post("/orders", body, "Idempotency-Key", key);
    }

    private int orderRows() {
        return dsl.fetchCount(ORDERS);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
