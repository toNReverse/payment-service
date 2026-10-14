package io.github.tonreverse.payment.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for Idempotency-Keys. A request first reserves the key, then either completes it (its order is
 * saved) or releases it (it failed, so the client may retry with the same key).
 *
 * <p>The {@code lockedAt} passed to {@link #complete} and {@link #release} is the reservation's fencing
 * token: the call only succeeds if the key is still held with that exact timestamp. A request that was
 * so slow that another took its reservation over can therefore no longer complete or release it.
 * Timestamps must have at most microsecond precision, like Postgres, or the comparison fails.
 */
public interface IdempotencyKeyRepository {

    /** Atomic: of several concurrent calls for the same key, exactly one returns true. */
    boolean tryReserve(String key, String requestHash, Instant now);

    Optional<IdempotencyRecord> find(String key);

    /**
     * Takes over an IN_PROGRESS reservation whose holder seems to have died. Succeeds only if the
     * reservation is unchanged since it was read with {@code seenLockedAt}, so two requests cannot both
     * take it over.
     */
    boolean tryTakeOver(String key, Instant seenLockedAt, Instant now);

    /** Marks the reservation as done. False if we no longer hold it. */
    boolean complete(String key, Instant lockedAt, UUID orderId);

    /** Gives up our reservation. Does nothing if we no longer hold it. */
    void release(String key, Instant lockedAt);
}
