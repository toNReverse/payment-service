package io.github.tonreverse.payment.persistence;

import static io.github.tonreverse.payment.persistence.jooq.Tables.IDEMPOTENCY_KEYS;

import io.github.tonreverse.payment.persistence.IdempotencyRecord.State;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

public final class JooqIdempotencyKeyRepository implements IdempotencyKeyRepository {

    private final DSLContext dsl;

    public JooqIdempotencyKeyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean tryReserve(String key, String requestHash, Instant now) {
        // Check and insert in one statement. A SELECT followed by an INSERT would let two concurrent
        // requests both see "no row" and both proceed; here the primary key lets only one insert win.
        int inserted = dsl.insertInto(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
                .set(IDEMPOTENCY_KEYS.STATE, State.IN_PROGRESS.name())
                .set(IDEMPOTENCY_KEYS.LOCKED_AT, toOffsetDateTime(now))
                .set(IDEMPOTENCY_KEYS.CREATED_AT, toOffsetDateTime(now))
                .onConflictDoNothing()
                .execute();
        return inserted == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return dsl.selectFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(row -> new IdempotencyRecord(
                        row.getIdempotencyKey(),
                        row.getRequestHash(),
                        State.valueOf(row.getState()),
                        row.getOrderId(),
                        row.getLockedAt().toInstant()));
    }

    // The next three are compare-and-set updates: the WHERE clause names the exact reservation the
    // caller saw or holds, and the row count says whether it was still there.

    @Override
    public boolean tryTakeOver(String key, Instant seenLockedAt, Instant now) {
        return dsl.update(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.LOCKED_AT, toOffsetDateTime(now))
                .where(heldWith(key, seenLockedAt))
                .execute() == 1;
    }

    @Override
    public boolean complete(String key, Instant lockedAt, UUID orderId) {
        return dsl.update(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.STATE, State.COMPLETED.name())
                .set(IDEMPOTENCY_KEYS.ORDER_ID, orderId)
                .where(heldWith(key, lockedAt))
                .execute() == 1;
    }

    @Override
    public void release(String key, Instant lockedAt) {
        dsl.deleteFrom(IDEMPOTENCY_KEYS).where(heldWith(key, lockedAt)).execute();
    }

    private static Condition heldWith(String key, Instant lockedAt) {
        return IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key)
                .and(IDEMPOTENCY_KEYS.STATE.eq(State.IN_PROGRESS.name()))
                .and(IDEMPOTENCY_KEYS.LOCKED_AT.eq(toOffsetDateTime(lockedAt)));
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
