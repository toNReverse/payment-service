package io.github.tonreverse.payment.api;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noOrders;
import static io.github.tonreverse.payment.api.TestServer.noHealthChecks;
import static io.github.tonreverse.payment.api.TestServer.noProducts;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.persistence.ProductRepository;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Every error, expected or not, comes back as {"error": "...", "details": [...]}. */
class ErrorHandlingTest {

    @Test
    void unknownRoutesGetAJson404() throws Exception {
        try (TestServer server = TestServer.start(noHealthChecks(), noProducts(), noOrders())) {
            HttpResponse<String> response = server.get("/does-not-exist");

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/json"));
            JsonNode body = json(response);
            assertThat(body.path("error").stringValue()).isEqualTo("not_found");
            assertThat(body.path("details").isArray()).isTrue();
        }
    }

    @Test
    void unexpectedErrorsBecomeA500WithoutInternalDetails() throws Exception {
        ProductController failing = new ProductController(new ProductRepository() {
            @Override
            public List<Product> findActive() {
                throw new IllegalStateException("connection to db-internal-7:5432 refused for user payments");
            }

            @Override
            public List<Product> findByIds(Collection<String> ids) {
                return List.of();
            }
        });

        try (TestServer server = TestServer.start(noHealthChecks(), failing, noOrders())) {
            HttpResponse<String> response = server.get("/products");

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(json(response).path("error").stringValue()).isEqualTo("internal_error");
            assertThat(response.body())
                    .doesNotContain("db-internal-7")
                    .doesNotContain("IllegalStateException")
                    .doesNotContain("at io.github");
        }
    }
}
