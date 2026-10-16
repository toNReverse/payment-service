package io.github.tonreverse.payment.testing;

import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.persistence.ProductRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class InMemoryProductRepository implements ProductRepository {

    private final List<Product> products;

    public InMemoryProductRepository(Product... products) {
        this.products = List.of(products);
    }

    @Override
    public List<Product> findActive() {
        return products.stream().filter(Product::active).sorted(Comparator.comparing(Product::name)).toList();
    }

    @Override
    public List<Product> findByIds(Collection<String> ids) {
        return products.stream().filter(product -> ids.contains(product.id())).toList();
    }
}
