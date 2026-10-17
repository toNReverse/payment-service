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

    /** No locking needed: tests using this fake run on one thread. */
    @Override
    public Optional<Order> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    @Override
    public void updateStatus(Order order) {
        if (orders.replace(order.id(), order) == null) {
            throw new IllegalStateException("order " + order.id() + " does not exist");
        }
    }

    public int count() {
        return orders.size();
    }
}
