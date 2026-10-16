package io.github.tonreverse.payment.api;

import static io.github.tonreverse.payment.api.TestServer.json;
import static io.github.tonreverse.payment.api.TestServer.noOrders;
import static io.github.tonreverse.payment.api.TestServer.noProducts;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.health.HealthCheck;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Fake health checks, so no database or Redis is needed. */
class HealthEndpointTest {

    @Test
    void returns200WhenEveryCheckIsUp() throws Exception {
        try (TestServer server = startWith(fakeCheck("database", true), fakeCheck("redis", true))) {
            HttpResponse<String> response = server.get("/health");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/json"));
            JsonNode body = json(response);
            assertThat(body.path("status").stringValue()).isEqualTo("UP");
            assertThat(body.path("checks").path("database").stringValue()).isEqualTo("UP");
            assertThat(body.path("checks").path("redis").stringValue()).isEqualTo("UP");
        }
    }

    @Test
    void returns503AndShowsWhichCheckIsDown() throws Exception {
        try (TestServer server = startWith(fakeCheck("database", true), fakeCheck("redis", false))) {
            HttpResponse<String> response = server.get("/health");

            assertThat(response.statusCode()).isEqualTo(503);
            JsonNode body = json(response);
            assertThat(body.path("status").stringValue()).isEqualTo("DOWN");
            assertThat(body.path("checks").path("database").stringValue()).isEqualTo("UP");
            assertThat(body.path("checks").path("redis").stringValue()).isEqualTo("DOWN");
        }
    }

    private static TestServer startWith(HealthCheck... checks) {
        return TestServer.start(new HealthController(List.of(checks)), noProducts(), noOrders());
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
