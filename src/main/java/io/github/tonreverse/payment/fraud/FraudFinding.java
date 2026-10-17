package io.github.tonreverse.payment.fraud;

/** A rule that fired and its reason; becomes a fraud_flags row. */
public record FraudFinding(String rule, String reason) {}
