package io.github.tonreverse.payment.api;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noOrders;
import static io.github.tonreverse.payment.api.TestServer.noHealthChecks;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.testing.InMemoryProductRepository;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Checks the HTTP contract of GET /products with an in-memory catalog. The SQL behind it (only active
 * products, sorted by name) is covered by JooqProductRepositoryTest against a real Postgres.
 */
class ProductsEndpointTest {

    @Test
    void listsTheCatalogWithPricesInMinorUnits() throws Exception {
        InMemoryProductRepository catalog = new InMemoryProductRepository(
                new Product("pandoro", "Pandoro 1 kg", Money.of(2_600, "EUR"), true),
                new Product("panettone-classico", "Panettone Classico 1 kg", Money.of(2_800, "EUR"), true));

        try (TestServer server = TestServer.start(noHealthChecks(), new ProductController(catalog), noOrders())) {
            HttpResponse<String> response = server.get("/products");

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode products = json(response).path("products");
            assertThat(products.size()).isEqualTo(2);
            JsonNode first = products.get(0);
            assertThat(first.path("id").stringValue()).isEqualTo("pandoro");
            assertThat(first.path("name").stringValue()).isEqualTo("Pandoro 1 kg");
            assertThat(first.path("price").path("amountMinor").longValue()).isEqualTo(2_600);
            assertThat(first.path("price").path("currency").stringValue()).isEqualTo("EUR");
            assertThat(products.get(1).path("id").stringValue()).isEqualTo("panettone-classico");
        }
    }

    @Test
    void returnsAnEmptyListWhenNothingIsOnSale() throws Exception {
        try (TestServer server = TestServer.start(noHealthChecks(), new ProductController(new InMemoryProductRepository()), noOrders())) {
            HttpResponse<String> response = server.get("/products");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("products").isArray()).isTrue();
            assertThat(json(response).path("products").size()).isZero();
        }
    }
}
