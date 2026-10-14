package io.github.tonreverse.payment.persistence;

import io.github.tonreverse.payment.domain.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    /** Saves the order and its items atomically: either all rows are written or none. */
    void insert(Order order);

    Optional<Order> findById(UUID id);
}
