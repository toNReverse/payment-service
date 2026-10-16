package io.github.tonreverse.payment.testing;

import io.github.tonreverse.payment.persistence.IdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.IdempotencyRecord;
import io.github.tonreverse.payment.persistence.IdempotencyRecord.State;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Same contract as the jOOQ version, with {@code synchronized} standing in for the database's atomic
 * statements. JooqIdempotencyKeyRepositoryTest checks that the real one behaves this way.
 */
public final class InMemoryIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private final Map<String, IdempotencyRecord> records = new HashMap<>();

    @Override
    public synchronized boolean tryReserve(String key, String requestHash, Instant now) {
        if (records.containsKey(key)) {
            return false;
        }
        records.put(key, new IdempotencyRecord(key, requestHash, State.IN_PROGRESS, null, now));
        return true;
    }

    @Override
    public synchronized Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(records.get(key));
    }

    @Override
    public synchronized boolean tryTakeOver(String key, Instant seenLockedAt, Instant now) {
        IdempotencyRecord record = records.get(key);
        if (!heldWith(record, seenLockedAt)) {
            return false;
        }
        records.put(key, new IdempotencyRecord(key, record.requestHash(), State.IN_PROGRESS, null, now));
        return true;
    }

    @Override
    public synchronized boolean complete(String key, Instant lockedAt, UUID orderId) {
        IdempotencyRecord record = records.get(key);
        if (!heldWith(record, lockedAt)) {
            return false;
        }
        records.put(key, new IdempotencyRecord(key, record.requestHash(), State.COMPLETED, orderId, lockedAt));
        return true;
    }

    @Override
    public synchronized void release(String key, Instant lockedAt) {
        if (heldWith(records.get(key), lockedAt)) {
            records.remove(key);
        }
    }

    private static boolean heldWith(IdempotencyRecord record, Instant lockedAt) {
        return record != null && record.state() == State.IN_PROGRESS && record.lockedAt().equals(lockedAt);
    }
}
