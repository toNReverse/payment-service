package io.github.tonreverse.payment.payment;

/**
 * A verified webhook: the provider's event id (the idempotency key for processing), its type and raw
 * payload (stored for audits), and what it means for us.
 */
public record PaymentNotification(String eventId, String eventType, String payload, PaymentUpdate update) {}
