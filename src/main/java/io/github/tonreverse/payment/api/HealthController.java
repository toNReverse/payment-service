package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.health.HealthCheck;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /health: 200 when every dependency answers, 503 otherwise, with the status of each one.
 * See docs/decisions.md for why a failing dependency turns the whole endpoint into a 503.
 */
public final class HealthController {

    enum Status { UP, DOWN }

    record HealthResponse(Status status, Map<String, Status> checks) {}

    private final List<HealthCheck> checks;

    public HealthController(List<HealthCheck> checks) {
        this.checks = List.copyOf(checks);
    }

    public void handle(Context ctx) {
        Map<String, Status> results = new LinkedHashMap<>();
        boolean allUp = true;
        for (HealthCheck check : checks) {
            boolean up = check.isHealthy();
            results.put(check.name(), up ? Status.UP : Status.DOWN);
            allUp = allUp && up;
        }

        ctx.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .json(new HealthResponse(allUp ? Status.UP : Status.DOWN, results));
    }
}
