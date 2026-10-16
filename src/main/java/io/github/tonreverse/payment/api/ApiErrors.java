package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.payment.InvalidWebhookException;
import io.github.tonreverse.payment.payment.PaymentGatewayException;
import io.github.tonreverse.payment.service.IdempotencyKeyInUseException;
import io.github.tonreverse.payment.service.IdempotencyKeyReusedException;
import io.github.tonreverse.payment.service.NotFoundException;
import io.github.tonreverse.payment.service.PaymentEventOutOfOrderException;
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

        // Idempotency (see docs/decisions.md). 422 and 409 follow the IETF Idempotency-Key draft.
        routes.exception(IdempotencyKeyReusedException.class, (e, ctx) ->
                respond(ctx, HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused", List.of(e.getMessage())));
        routes.exception(IdempotencyKeyInUseException.class, (e, ctx) -> {
            ctx.header("Retry-After", "1");
            respond(ctx, HttpStatus.CONFLICT, "idempotency_key_in_use", List.of(e.getMessage()));
        });

        // The provider's own message may name hosts or contain request ids: log it, do not return it.
        routes.exception(PaymentGatewayException.class, (e, ctx) -> {
            log.warn("Payment provider failed on {} {}", ctx.method(), ctx.path(), e);
            respond(ctx, HttpStatus.BAD_GATEWAY, "payment_provider_unavailable",
                    List.of("The payment provider could not be reached. Retry with the same Idempotency-Key."));
        });

        // Stripe webhooks: Stripe retries any non-2xx answer (see StripeWebhookController).
        routes.exception(InvalidWebhookException.class, (e, ctx) -> {
            log.warn("Rejected webhook on {}: {}", ctx.path(), e.getMessage());
            respond(ctx, HttpStatus.BAD_REQUEST, "invalid_webhook", List.of(e.getMessage()));
        });
        routes.exception(PaymentEventOutOfOrderException.class, (e, ctx) -> {
            log.info("Webhook arrived early, Stripe will retry: {}", e.getMessage());
            respond(ctx, HttpStatus.CONFLICT, "event_out_of_order", List.of(e.getMessage()));
        });
        routes.exception(WebhooksNotConfiguredException.class,
                (e, ctx) -> respond(ctx, HttpStatus.SERVICE_UNAVAILABLE, "webhooks_not_configured", List.of(e.getMessage())));

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
