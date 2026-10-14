package io.github.tonreverse.payment.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * What the database knows about one Idempotency-Key.
 *
 * @param orderId the order created under this key, set once the state is COMPLETED
 * @param lockedAt when the current holder reserved the key
 */
public record IdempotencyRecord(String key, String requestHash, State state, UUID orderId, Instant lockedAt) {

    public enum State { IN_PROGRESS, COMPLETED }
}
