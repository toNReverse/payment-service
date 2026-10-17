package io.github.tonreverse.payment.persistence;

import static io.github.tonreverse.payment.persistence.jooq.Tables.PAYMENT_EVENTS;

import io.github.tonreverse.payment.persistence.PaymentEventRecord.Outcome;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;

public final class JooqPaymentEventRepository implements PaymentEventRepository {

    private final DSLContext dsl;

    public JooqPaymentEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean exists(String eventId) {
        return dsl.fetchExists(PAYMENT_EVENTS, PAYMENT_EVENTS.STRIPE_EVENT_ID.eq(eventId));
    }

    @Override
    public boolean tryInsert(PaymentEventRecord event) {
        return dsl.insertInto(PAYMENT_EVENTS)
                .set(PAYMENT_EVENTS.STRIPE_EVENT_ID, event.eventId())
                .set(PAYMENT_EVENTS.EVENT_TYPE, event.eventType())
                .set(PAYMENT_EVENTS.ORDER_ID, event.orderId())
                .set(PAYMENT_EVENTS.OUTCOME, event.outcome().name())
                .set(PAYMENT_EVENTS.NOTE, event.note())
                .set(PAYMENT_EVENTS.PAYLOAD, JSONB.valueOf(event.payload()))
                .set(PAYMENT_EVENTS.RECEIVED_AT, OffsetDateTime.ofInstant(event.receivedAt(), ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute() == 1;
    }

    @Override
    public List<PaymentEventRecord> findByOrderId(UUID orderId) {
        return dsl.selectFrom(PAYMENT_EVENTS)
                .where(PAYMENT_EVENTS.ORDER_ID.eq(orderId))
                .orderBy(PAYMENT_EVENTS.RECEIVED_AT, PAYMENT_EVENTS.STRIPE_EVENT_ID)
                .fetch(row -> new PaymentEventRecord(
                        row.getStripeEventId(),
                        row.getEventType(),
                        row.getOrderId(),
                        Outcome.valueOf(row.getOutcome()),
                        row.getNote(),
                        row.getPayload().data(),
                        row.getReceivedAt().toInstant()));
    }
}
