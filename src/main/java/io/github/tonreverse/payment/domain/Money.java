package io.github.tonreverse.payment.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money: a whole number of the currency's minor unit (cents for EUR) plus an ISO 4217
 * currency. Never a float or double, because binary floating point cannot represent 0.10 exactly.
 *
 * <p>Amounts are never negative: this service only deals with prices and order totals, and a refund is
 * a status change of the order, not a negative amount. Arithmetic uses {@link Math#addExact} and
 * {@link Math#multiplyExact}, so an overflow throws instead of silently wrapping around to a negative
 * number.
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("Money cannot be negative, got " + amountMinor);
        }
        // ISO 4217 also lists non-monetary codes (XAU gold, XXX "no currency"); they have no minor unit.
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(currency.getCurrencyCode() + " is not a monetary currency");
        }
    }

    /** @throws IllegalArgumentException if the code is not an upper-case ISO 4217 code */
    public static Money of(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money times(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative, got " + quantity);
        }
        return new Money(Math.multiplyExact(amountMinor, quantity), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amountMinor > other.amountMinor;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " and " + other.currency);
        }
    }
}
