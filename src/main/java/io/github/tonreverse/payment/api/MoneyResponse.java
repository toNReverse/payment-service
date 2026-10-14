package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.domain.Money;

/**
 * Money on the wire: integer minor units plus the ISO code, e.g. {"amountMinor": 2800, "currency": "EUR"}.
 * Same convention as Stripe's API, and clients never have to parse a decimal string.
 */
public record MoneyResponse(long amountMinor, String currency) {

    public static MoneyResponse from(Money money) {
        return new MoneyResponse(money.amountMinor(), money.currency().getCurrencyCode());
    }
}
