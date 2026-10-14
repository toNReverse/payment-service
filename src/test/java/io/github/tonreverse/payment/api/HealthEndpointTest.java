package io.github.tonreverse.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.App;
import io.github.tonreverse.payment.health.HealthCheck;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the real HTTP server (same routes and JSON mapper as production) on a random free port,
 * but with fake health checks, so no database or Redis is needed.
 */
class HealthEndpointTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private Javalin server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void returns200WhenEveryCheckIsUp() throws Exception {
        startServerWith(fakeCheck("database", true), fakeCheck("redis", true));

        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        JsonNode body = JsonMapper.shared().readTree(response.body());
        assertThat(body.path("status").stringValue()).isEqualTo("UP");
        assertThat(body.path("checks").path("database").stringValue()).isEqualTo("UP");
        assertThat(body.path("checks").path("redis").stringValue()).isEqualTo("UP");
    }

    @Test
    void returns503AndShowsWhichCheckIsDown() throws Exception {
        startServerWith(fakeCheck("database", true), fakeCheck("redis", false));

        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode()).isEqualTo(503);
        JsonNode body = JsonMapper.shared().readTree(response.body());
        assertThat(body.path("status").stringValue()).isEqualTo("DOWN");
        assertThat(body.path("checks").path("database").stringValue()).isEqualTo("UP");
        assertThat(body.path("checks").path("redis").stringValue()).isEqualTo("DOWN");
    }

    private void startServerWith(HealthCheck... checks) {
        server = App.createServer(new HealthController(List.of(checks))).start(0);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HealthCheck fakeCheck(String name, boolean healthy) {
        return new HealthCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isHealthy() {
                return healthy;
            }
        };
    }
}
