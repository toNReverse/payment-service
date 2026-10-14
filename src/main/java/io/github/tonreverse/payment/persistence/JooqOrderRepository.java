package io.github.tonreverse.payment.persistence;

import static io.github.tonreverse.payment.persistence.jooq.Tables.ORDERS;
import static io.github.tonreverse.payment.persistence.jooq.Tables.ORDER_ITEMS;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.domain.OrderStatus;
import io.github.tonreverse.payment.persistence.jooq.tables.records.OrdersRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqOrderRepository implements OrderRepository {

    private final DSLContext dsl;

    public JooqOrderRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Order order) {
        // One transaction: if any item insert fails, the order row is rolled back too.
        CheckoutSession checkout = order.checkoutSession();
        dsl.transaction(transaction -> {
            DSLContext tx = transaction.dsl();

            tx.insertInto(ORDERS)
                    .set(ORDERS.ID, order.id())
                    .set(ORDERS.CUSTOMER_EMAIL, order.customerEmail())
                    .set(ORDERS.TOTAL_MINOR, order.total().amountMinor())
                    .set(ORDERS.CURRENCY, order.total().currency().getCurrencyCode())
                    .set(ORDERS.STATUS, order.status().name())
                    .set(ORDERS.CHECKOUT_SESSION_ID, checkout == null ? null : checkout.id())
                    .set(ORDERS.CHECKOUT_URL, checkout == null ? null : checkout.url())
                    .set(ORDERS.CREATED_AT, toOffsetDateTime(order.createdAt()))
                    .set(ORDERS.UPDATED_AT, toOffsetDateTime(order.updatedAt()))
                    .execute();

            // All items in a single multi-row INSERT.
            var items = tx.insertInto(ORDER_ITEMS,
                    ORDER_ITEMS.ORDER_ID, ORDER_ITEMS.PRODUCT_ID, ORDER_ITEMS.QUANTITY, ORDER_ITEMS.UNIT_PRICE_MINOR);
            for (OrderItem item : order.items()) {
                items = items.values(order.id(), item.productId(), item.quantity(), item.unitPrice().amountMinor());
            }
            items.execute();
        });
    }

    @Override
    public Optional<Order> findById(UUID id) {
        OrdersRecord row = dsl.selectFrom(ORDERS).where(ORDERS.ID.eq(id)).fetchOne();
        if (row == null) {
            return Optional.empty();
        }

        // Items have no currency column: an order has a single currency, stored on the order row.
        Currency currency = Currency.getInstance(row.getCurrency());
        List<OrderItem> items = dsl.selectFrom(ORDER_ITEMS)
                .where(ORDER_ITEMS.ORDER_ID.eq(id))
                .orderBy(ORDER_ITEMS.PRODUCT_ID)
                .fetch(item -> new OrderItem(
                        item.getProductId(), item.getQuantity(), new Money(item.getUnitPriceMinor(), currency)));

        // The Order constructor re-checks every invariant, so a corrupted row fails loudly here.
        return Optional.of(new Order(
                row.getId(),
                row.getCustomerEmail(),
                items,
                new Money(row.getTotalMinor(), currency),
                OrderStatus.valueOf(row.getStatus()),
                row.getCheckoutSessionId() == null
                        ? null
                        : new CheckoutSession(row.getCheckoutSessionId(), row.getCheckoutUrl()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant()));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
