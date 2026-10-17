package io.github.tonreverse.payment.persistence;

import io.github.tonreverse.payment.domain.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    /** Saves the order and its items atomically: either all rows are written or none. */
    void insert(Order order);

    Optional<Order> findById(UUID id);

    /**
     * Like {@link #findById}, and locks the order until the current transaction ends. Only meaningful
     * inside {@link Transactions#inTransaction}: outside one, the lock is released as soon as the query ends.
     */
    Optional<Order> findByIdForUpdate(UUID id);

    /** Saves a new status and update time. Everything else about an order is fixed when it is created. */
    void updateStatus(Order order);
}
