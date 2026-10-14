package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.App;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real HTTP server (same routes and JSON mapper as production) on a random free port, built from
 * whatever controllers a test passes in. Use with try-with-resources so the server always stops.
 */
final class TestServer implements AutoCloseable {

    private final Javalin server;
    private final HttpClient http = HttpClient.newHttpClient();

    private TestServer(Javalin server) {
        this.server = server;
    }

    static TestServer start(HealthController health, ProductController products) {
        return new TestServer(App.createServer(health, products).start(0));
    }

    /** For tests that do not care about health checks. */
    static HealthController noHealthChecks() {
        return new HealthController(List.of());
    }

    /** For tests that do not care about the catalog. */
    static ProductController noProducts() {
        return new ProductController(List::of);
    }

    HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> response) {
        return JsonMapper.shared().readTree(response.body());
    }

    @Override
    public void close() {
        server.stop();
    }
}
