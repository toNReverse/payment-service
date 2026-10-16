package io.github.tonreverse.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.FraudFlag;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JooqFraudFlagRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30.123456Z");

    private final JooqFraudFlagRepository flags = new JooqFraudFlagRepository(TestDatabase.dsl());
    private final JooqOrderRepository orders = new JooqOrderRepository(TestDatabase.dsl());

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
    }

    @Test
    void storesFlagsAndFindsThemByOrder() {
        Order order = Order.create(UUID.randomUUID(), "anna@example.com",
                List.of(new OrderItem("pandoro", "Pandoro 1 kg", 1, Money.of(2_600, "EUR"))), NOW);
        orders.insert(order);
        FraudFlag flag = new FraudFlag(UUID.randomUUID(), order.id(), "payment_amount_mismatch",
                "Stripe charged 2000 EUR, the order total is 2600 EUR", NOW);

        flags.insert(flag);

        assertThat(flags.findByOrderId(order.id())).containsExactly(flag);
        assertThat(flags.findByOrderId(UUID.randomUUID())).isEmpty();
    }
}
