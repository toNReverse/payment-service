package io.github.tonreverse.payment.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * One received Stripe webhook and what was done with it.
 *
 * @param orderId the order it was applied to, or null if it matched none
 * @param payload the event JSON as received
 */
public record PaymentEventRecord(
        String eventId, String eventType, UUID orderId, Outcome outcome, String note, String payload, Instant receivedAt) {

    public enum Outcome { APPLIED, FLAGGED, RECORDED, IGNORED }
}
