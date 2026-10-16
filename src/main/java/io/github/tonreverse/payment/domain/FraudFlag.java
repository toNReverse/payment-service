package io.github.tonreverse.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Why an order was set to FLAGGED, for a human to review.
 *
 * @param rule short machine-readable name of what triggered it, e.g. "payment_amount_mismatch"
 * @param reason the details, e.g. which amounts differed
 */
public record FraudFlag(UUID id, UUID orderId, String rule, String reason, Instant createdAt) {

    public FraudFlag {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("rule is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
