package io.github.tonreverse.payment.persistence;

import static io.github.tonreverse.payment.persistence.jooq.Tables.FRAUD_FLAGS;

import io.github.tonreverse.payment.domain.FraudFlag;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqFraudFlagRepository implements FraudFlagRepository {

    private final DSLContext dsl;

    public JooqFraudFlagRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(FraudFlag flag) {
        dsl.insertInto(FRAUD_FLAGS)
                .set(FRAUD_FLAGS.ID, flag.id())
                .set(FRAUD_FLAGS.ORDER_ID, flag.orderId())
                .set(FRAUD_FLAGS.RULE, flag.rule())
                .set(FRAUD_FLAGS.REASON, flag.reason())
                .set(FRAUD_FLAGS.CREATED_AT, OffsetDateTime.ofInstant(flag.createdAt(), ZoneOffset.UTC))
                .execute();
    }

    @Override
    public List<FraudFlag> findByOrderId(UUID orderId) {
        return dsl.selectFrom(FRAUD_FLAGS)
                .where(FRAUD_FLAGS.ORDER_ID.eq(orderId))
                .orderBy(FRAUD_FLAGS.CREATED_AT, FRAUD_FLAGS.ID)
                .fetch(row -> new FraudFlag(
                        row.getId(), row.getOrderId(), row.getRule(), row.getReason(), row.getCreatedAt().toInstant()));
    }
}
