package io.github.tonreverse.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductTest {

    @Test
    void holdsItsCatalogData() {
        Product product = new Product("pandoro", "Pandoro 1 kg", Money.of(2_600, "EUR"), true);

        assertThat(product.id()).isEqualTo("pandoro");
        assertThat(product.name()).isEqualTo("Pandoro 1 kg");
        assertThat(product.price()).isEqualTo(Money.of(2_600, "EUR"));
        assertThat(product.active()).isTrue();
    }

    @Test
    void requiresIdAndName() {
        Money price = Money.of(2_600, "EUR");

        assertThatThrownBy(() -> new Product("", "Pandoro 1 kg", price, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Product("pandoro", " ", price, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAPrice() {
        assertThatThrownBy(() -> new Product("pandoro", "Pandoro 1 kg", null, true))
                .isInstanceOf(NullPointerException.class);
    }
}
