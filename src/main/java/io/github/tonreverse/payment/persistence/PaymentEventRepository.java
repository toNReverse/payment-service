package io.github.tonreverse.payment.persistence;

import java.util.List;
import java.util.UUID;

public interface PaymentEventRepository {

    boolean exists(String eventId);

    /** False if an event with this id is already stored. */
    boolean tryInsert(PaymentEventRecord event);

    /** Oldest first. */
    List<PaymentEventRecord> findByOrderId(UUID orderId);
}
