package io.github.tonreverse.payment.config;

import io.github.tonreverse.payment.domain.Money;
import java.time.Duration;

/**
 * Thresholds of the fraud rules. Orders above them are not refused: they are saved FLAGGED for review.
 *
 * @param maxOrderTotal in EUR, the currency of the catalog
 */
public record FraudSettings(Money maxOrderTotal, int maxQuantityPerProduct, int maxOrdersPerEmail, Duration ordersPerEmailWindow) {}
