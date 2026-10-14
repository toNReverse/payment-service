package io.github.tonreverse.payment.persistence;

import io.github.tonreverse.payment.domain.Product;
import java.util.List;

/**
 * Repositories are interfaces so the HTTP and service tests can use in-memory fakes. The jOOQ
 * implementations are tested separately against a real Postgres.
 */
public interface ProductRepository {

    /** Products that can be ordered, sorted by name. */
    List<Product> findActive();
}
