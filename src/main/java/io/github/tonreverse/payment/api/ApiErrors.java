package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.service.NotFoundException;
import io.github.tonreverse.payment.service.ValidationException;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps exceptions to HTTP responses in one place. Every error body has the same shape:
 * {"error": "machine_readable_code", "details": ["human readable", ...]}.
 *
 * <p>Handlers, services and the domain only throw; none of them builds an error response.
 */
public final class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    record ErrorResponse(String error, List<String> details) {}

    private ApiErrors() {}

    public static void register(RoutesConfig routes) {
        routes.exception(ValidationException.class,
                (e, ctx) -> respond(ctx, HttpStatus.BAD_REQUEST, "validation_failed", e.details()));
        routes.exception(NotFoundException.class,
                (e, ctx) -> respond(ctx, HttpStatus.NOT_FOUND, "not_found", List.of(e.getMessage())));

        // Javalin's own errors, such as EndpointNotFound for an unknown route.
        routes.exception(HttpResponseException.class, (e, ctx) -> {
            HttpStatus status = HttpStatus.forStatus(e.getStatus());
            respond(ctx, status, status.name().toLowerCase(Locale.ROOT), List.of(e.getMessage()));
        });

        // Anything else is a bug or an outage. The details go to the log, never to the client: exception
        // messages can contain hostnames, SQL or customer data.
        routes.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            respond(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", List.of("An unexpected error occurred"));
        });
    }

    static void respond(Context ctx, HttpStatus status, String error, List<String> details) {
        ctx.status(status).json(new ErrorResponse(error, details));
    }
}
