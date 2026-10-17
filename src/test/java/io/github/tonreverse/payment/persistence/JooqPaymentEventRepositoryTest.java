package io.github.tonreverse.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.persistence.PaymentEventRecord.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class JooqPaymentEventRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30.123456Z");
    private static final String PAYLOAD = "{\"id\": \"evt_test_1\", \"type\": \"checkout.session.completed\"}";

    private final JooqPaymentEventRepository events = new JooqPaymentEventRepository(TestDatabase.dsl());
    private final JooqOrderRepository orders = new JooqOrderRepository(TestDatabase.dsl());

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
    }

    @Test
    void storesAnEventAndFindsItByOrder() {
        UUID orderId = insertOrder();
        PaymentEventRecord event = new PaymentEventRecord(
                "evt_test_1", "checkout.session.completed", orderId, Outcome.APPLIED, "PENDING_PAYMENT -> PAID", PAYLOAD, NOW);

        assertThat(events.tryInsert(event)).isTrue();

        List<PaymentEventRecord> found = events.findByOrderId(orderId);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst()).usingRecursiveComparison().ignoringFields("payload").isEqualTo(event);
        // JSONB normalizes whitespace, so compare the JSON, not the text.
        assertThat(JsonMapper.shared().readTree(found.getFirst().payload())).isEqualTo(JsonMapper.shared().readTree(PAYLOAD));
    }

    @Test
    void theSameStripeEventIdCanOnlyBeStoredOnce() {
        PaymentEventRecord event = new PaymentEventRecord(
                "evt_test_1", "checkout.session.expired", null, Outcome.IGNORED, "no order", PAYLOAD, NOW);

        assertThat(events.exists("evt_test_1")).isFalse();
        assertThat(events.tryInsert(event)).isTrue();
        assertThat(events.exists("evt_test_1")).isTrue();
        assertThat(events.tryInsert(event)).isFalse();
    }

    private UUID insertOrder() {
        Order order = Order.create(UUID.randomUUID(), "anna@example.com",
                List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), NOW);
        orders.insert(order);
        return order.id();
    }
}
