package io.github.tonreverse.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final UUID ID = UUID.fromString("7d3f2a10-0000-4000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-12-01T10:15:30.123456Z");

    private static OrderItem item(String productId, int quantity, long unitPriceMinor) {
        return new OrderItem(productId, quantity, Money.of(unitPriceMinor, "EUR"));
    }

    @Nested
    class Items {

        @Test
        void lineTotalIsUnitPriceTimesQuantity() {
            assertThat(item("panettone-classico", 3, 2_800).lineTotal()).isEqualTo(Money.of(8_400, "EUR"));
        }

        @Test
        void quantityMustBePositive() {
            assertThatThrownBy(() -> item("panettone-classico", 0, 2_800))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quantity");
        }

        @Test
        void productIdIsRequired() {
            assertThatThrownBy(() -> item(" ", 1, 2_800)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Creation {

        @Test
        void startsAsCreatedWithTheTotalOfItsItems() {
            Order order = Order.create(ID, "anna@example.com",
                    List.of(item("panettone-classico", 2, 2_800), item("pandoro", 1, 2_600)), CREATED_AT);

            assertThat(order.id()).isEqualTo(ID);
            assertThat(order.customerEmail()).isEqualTo("anna@example.com");
            assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
            assertThat(order.total()).isEqualTo(Money.of(8_200, "EUR"));
            assertThat(order.checkoutSessionId()).isNull();
            assertThat(order.createdAt()).isEqualTo(CREATED_AT);
            assertThat(order.updatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        void needsAtLeastOneItem() {
            assertThatThrownBy(() -> Order.create(ID, "anna@example.com", List.of(), CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("item");
        }

        @Test
        void listsEachProductOnlyOnce() {
            List<OrderItem> items = List.of(item("pandoro", 1, 2_600), item("pandoro", 2, 2_600));

            assertThatThrownBy(() -> Order.create(ID, "anna@example.com", items, CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pandoro");
        }

        @Test
        void refusesItemsInDifferentCurrencies() {
            List<OrderItem> items = List.of(
                    item("pandoro", 1, 2_600), new OrderItem("panettone-classico", 1, Money.of(3_000, "USD")));

            assertThatThrownBy(() -> Order.create(ID, "anna@example.com", items, CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void customerEmailIsRequired() {
            List<OrderItem> items = List.of(item("pandoro", 1, 2_600));

            assertThatThrownBy(() -> Order.create(ID, "", items, CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void itemsCannotBeChangedFromOutside() {
            List<OrderItem> items = new ArrayList<>(List.of(item("pandoro", 1, 2_600)));
            Order order = Order.create(ID, "anna@example.com", items, CREATED_AT);

            items.add(item("panettone-classico", 1, 2_800));

            assertThat(order.items()).hasSize(1);
            assertThatThrownBy(() -> order.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Restoring {

        @Test
        void rejectsAStoredTotalThatDoesNotMatchTheItems() {
            // Guards against corrupted rows or a bug in the mapping code: the invariant holds for loaded orders too.
            List<OrderItem> items = List.of(item("pandoro", 2, 2_600));

            assertThatThrownBy(() -> new Order(ID, "anna@example.com", items, Money.of(1, "EUR"),
                    OrderStatus.PAID, "cs_test_123", CREATED_AT, CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("total");
        }
    }

    @Nested
    class StatusChanges {

        @Test
        void returnsAnUpdatedCopyAndLeavesTheOriginalUntouched() {
            Order created = Order.create(ID, "anna@example.com", List.of(item("pandoro", 1, 2_600)), CREATED_AT);
            Instant later = CREATED_AT.plusSeconds(60);

            Order pending = created.transitionTo(OrderStatus.PENDING_PAYMENT, later);

            assertThat(pending.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(pending.updatedAt()).isEqualTo(later);
            assertThat(pending.createdAt()).isEqualTo(CREATED_AT);
            assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
        }

        @Test
        void followsTheStateMachine() {
            Order created = Order.create(ID, "anna@example.com", List.of(item("pandoro", 1, 2_600)), CREATED_AT);

            assertThatThrownBy(() -> created.transitionTo(OrderStatus.PAID, CREATED_AT))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }
    }
}
