package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as POST /orders and GET /orders/{id} return it.
 *
 * @param checkoutUrl where the customer pays; only set while the order is waiting for payment, since the
 *     page is useless once the order is paid, failed or expired
 */
record OrderResponse(
        UUID id,
        String status,
        String customerEmail,
        List<ItemResponse> items,
        MoneyResponse total,
        String checkoutUrl,
        Instant createdAt,
        Instant updatedAt) {

    record ItemResponse(
            String productId, String productName, int quantity, MoneyResponse unitPrice, MoneyResponse lineTotal) {

        static ItemResponse from(OrderItem item) {
            return new ItemResponse(item.productId(), item.productName(), item.quantity(),
                    MoneyResponse.from(item.unitPrice()), MoneyResponse.from(item.lineTotal()));
        }
    }

    static OrderResponse from(Order order) {
        String checkoutUrl = order.status() == OrderStatus.PENDING_PAYMENT ? order.checkoutSession().url() : null;
        return new OrderResponse(
                order.id(),
                order.status().name(),
                order.customerEmail(),
                order.items().stream().map(ItemResponse::from).toList(),
                MoneyResponse.from(order.total()),
                checkoutUrl,
                order.createdAt(),
                order.updatedAt());
    }
}
