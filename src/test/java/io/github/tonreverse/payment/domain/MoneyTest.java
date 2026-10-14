package io.github.tonreverse.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Nested
    class Creation {

        @Test
        void holdsMinorUnitsAndCurrency() {
            Money money = Money.of(2_450, "EUR");

            assertThat(money.amountMinor()).isEqualTo(2_450);
            assertThat(money.currency()).isEqualTo(EUR);
        }

        @Test
        void allowsZero() {
            assertThat(Money.zero(EUR).amountMinor()).isZero();
        }

        @Test
        void rejectsNegativeAmounts() {
            assertThatThrownBy(() -> Money.of(-1, "EUR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        void rejectsMissingCurrency() {
            assertThatThrownBy(() -> new Money(100, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"EURO", "ABC", "", "eur"})
        void rejectsCodesThatAreNotIso4217(String code) {
            assertThatThrownBy(() -> Money.of(100, code))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"XAU", "XXX"})
        void rejectsIsoCodesThatAreNotMoney(String code) {
            // Gold and "no currency" are valid ISO 4217 codes but have no minor unit.
            assertThatThrownBy(() -> Money.of(100, code))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(code);
        }
    }

    @Nested
    class Equality {

        @Test
        void isEqualWhenAmountAndCurrencyMatch() {
            assertThat(Money.of(1_000, "EUR")).isEqualTo(Money.of(1_000, "EUR"));
        }

        @Test
        void isNotEqualWhenOnlyTheAmountMatches() {
            assertThat(Money.of(1_000, "EUR")).isNotEqualTo(Money.of(1_000, "USD"));
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addsAmountsInTheSameCurrency() {
            assertThat(Money.of(1_250, "EUR").plus(Money.of(750, "EUR"))).isEqualTo(Money.of(2_000, "EUR"));
        }

        @Test
        void refusesToAddDifferentCurrencies() {
            assertThatThrownBy(() -> Money.of(1_000, "EUR").plus(Money.of(1_000, "USD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll("EUR", "USD");
        }

        @Test
        void failsInsteadOfOverflowingOnAddition() {
            Money max = new Money(Long.MAX_VALUE, EUR);

            assertThatThrownBy(() -> max.plus(Money.of(1, "EUR"))).isInstanceOf(ArithmeticException.class);
        }

        @Test
        void multipliesByAQuantity() {
            assertThat(Money.of(2_450, "EUR").times(3)).isEqualTo(Money.of(7_350, "EUR"));
        }

        @Test
        void multiplyingByZeroGivesZero() {
            assertThat(Money.of(2_450, "EUR").times(0)).isEqualTo(Money.zero(EUR));
        }

        @Test
        void rejectsNegativeQuantities() {
            assertThatThrownBy(() -> Money.of(2_450, "EUR").times(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void failsInsteadOfOverflowingOnMultiplication() {
            Money large = new Money(Long.MAX_VALUE / 2 + 1, EUR);

            assertThatThrownBy(() -> large.times(2)).isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class Comparison {

        @Test
        void isGreaterThanASmallerAmount() {
            assertThat(Money.of(50_001, "EUR").isGreaterThan(Money.of(50_000, "EUR"))).isTrue();
        }

        @Test
        void isNotGreaterThanAnEqualOrLargerAmount() {
            assertThat(Money.of(50_000, "EUR").isGreaterThan(Money.of(50_000, "EUR"))).isFalse();
            assertThat(Money.of(49_999, "EUR").isGreaterThan(Money.of(50_000, "EUR"))).isFalse();
        }

        @Test
        void refusesToCompareDifferentCurrencies() {
            assertThatThrownBy(() -> Money.of(100, "EUR").isGreaterThan(Money.of(100, "GBP")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
