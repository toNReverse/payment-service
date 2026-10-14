package io.github.tonreverse.payment.persistence;

import static io.github.tonreverse.payment.persistence.jooq.Tables.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Product;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JooqProductRepositoryTest {

    private final DSLContext dsl = TestDatabase.dsl();
    private final JooqProductRepository repository = new JooqProductRepository(dsl);

    @BeforeEach
    void cleanUp() {
        TestDatabase.deleteTestData();
    }

    @Test
    void listsOnlyActiveProductsSortedByName() {
        // Names start with different letters so every collation sorts them the same way.
        insertProduct("test-gamma", "Gamma test product", 300, true);
        insertProduct("test-alpha", "Alpha test product", 100, true);
        insertProduct("test-beta", "Beta test product", 200, false);

        List<String> testProductIds = repository.findActive().stream()
                .map(Product::id)
                .filter(id -> id.startsWith("test-"))
                .toList();

        assertThat(testProductIds).containsExactly("test-alpha", "test-gamma");
    }

    @Test
    void mapsEveryColumnToTheDomainProduct() {
        insertProduct("test-alpha", "Alpha test product", 4_250, true);

        assertThat(repository.findActive())
                .contains(new Product("test-alpha", "Alpha test product", Money.of(4_250, "EUR"), true));
    }

    @Test
    void theSeedMigrationProvidesTheChristmasCatalog() {
        assertThat(repository.findActive())
                .extracting(Product::id)
                .contains("panettone-classico", "panettone-cioccolato", "panettone-pistacchio", "pandoro")
                .doesNotContain("panettone-limoncello");
    }

    private void insertProduct(String id, String name, long priceMinor, boolean active) {
        dsl.insertInto(PRODUCTS)
                .set(PRODUCTS.ID, id)
                .set(PRODUCTS.NAME, name)
                .set(PRODUCTS.PRICE_MINOR, priceMinor)
                .set(PRODUCTS.CURRENCY, "EUR")
                .set(PRODUCTS.ACTIVE, active)
                .execute();
    }
}
