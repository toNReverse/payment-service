package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.persistence.ProductRepository;
import io.javalin.http.Context;
import java.util.List;

/** GET /products: the active catalog. */
public final class ProductController {

    /** Response DTOs keep the JSON format independent of the domain classes. */
    record ProductResponse(String id, String name, MoneyResponse price) {

        static ProductResponse from(Product product) {
            return new ProductResponse(product.id(), product.name(), MoneyResponse.from(product.price()));
        }
    }

    /** An object rather than a bare array, so fields such as pagination can be added later. */
    record ProductListResponse(List<ProductResponse> products) {}

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    public void list(Context ctx) {
        List<ProductResponse> response = products.findActive().stream().map(ProductResponse::from).toList();
        ctx.json(new ProductListResponse(response));
    }
}
