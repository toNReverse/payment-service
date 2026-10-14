package io.github.tonreverse.payment.domain;

import java.util.Objects;

/** A catalog entry. Inactive products stay in the database for old orders but cannot be ordered. */
public record Product(String id, String name, Money price, boolean active) {

    public Product {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        Objects.requireNonNull(price, "price");
    }
}
