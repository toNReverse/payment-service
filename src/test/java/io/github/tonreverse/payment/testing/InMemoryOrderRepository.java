package io.github.tonreverse.payment.testing;

import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.persistence.OrderRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryOrderRepository implements OrderRepository {

    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();

    @Override
    public void insert(Order order) {
        if (orders.putIfAbsent(order.id(), order) != null) {
            throw new IllegalStateException("order " + order.id() + " already exists");
        }
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return Optional.ofNullable(orders.get(id));
    }

    public int count() {
        return orders.size();
    }
}
