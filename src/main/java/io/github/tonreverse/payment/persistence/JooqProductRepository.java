package io.github.tonreverse.payment.persistence;

import static io.github.tonreverse.payment.persistence.jooq.Tables.PRODUCTS;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.persistence.jooq.tables.records.ProductsRecord;
import java.util.Collection;
import java.util.List;
import org.jooq.DSLContext;

public final class JooqProductRepository implements ProductRepository {

    private final DSLContext dsl;

    public JooqProductRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Product> findActive() {
        return dsl.selectFrom(PRODUCTS)
                .where(PRODUCTS.ACTIVE.isTrue())
                .orderBy(PRODUCTS.NAME)
                .fetch(JooqProductRepository::toProduct);
    }

    @Override
    public List<Product> findByIds(Collection<String> ids) {
        return dsl.selectFrom(PRODUCTS)
                .where(PRODUCTS.ID.in(ids))
                .fetch(JooqProductRepository::toProduct);
    }

    /** Rows are mapped by hand: the domain classes know nothing about jOOQ or the table layout. */
    private static Product toProduct(ProductsRecord row) {
        return new Product(row.getId(), row.getName(), Money.of(row.getPriceMinor(), row.getCurrency()), row.getActive());
    }
}
