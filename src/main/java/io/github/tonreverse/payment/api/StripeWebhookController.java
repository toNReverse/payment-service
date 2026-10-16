package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.payment.PaymentNotification;
import io.github.tonreverse.payment.payment.StripeWebhookParser;
import io.github.tonreverse.payment.service.PaymentEventService;
import io.github.tonreverse.payment.service.PaymentEventService.Result;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;

/**
 * POST /webhooks/stripe. Stripe treats any 2xx as delivered and retries everything else for days, so:
 * 200 for every genuine event we have dealt with (applied, recorded, ignored or seen before), 400 for a
 * forged one, 409 for one that came too early (so it is retried), 503 if webhooks are not configured.
 */
public final class StripeWebhookController {

    record WebhookResponse(boolean received, String result) {}

    private final StripeWebhookParser parser;
    private final PaymentEventService paymentEvents;

    public StripeWebhookController(StripeWebhookParser parser, PaymentEventService paymentEvents) {
        this.parser = parser;
        this.paymentEvents = paymentEvents;
    }

    /** For an instance without a signing secret: unsigned events must never be trusted, so refuse them all. */
    public static StripeWebhookController notConfigured() {
        return new StripeWebhookController(null, null);
    }

    public void handle(Context ctx) {
        if (parser == null) {
            throw new WebhooksNotConfiguredException();
        }
        // The signature covers the exact bytes Stripe sent, so they are read as they are, before any parsing.
        String payload = new String(ctx.bodyAsBytes(), StandardCharsets.UTF_8);
        PaymentNotification notification = parser.parse(payload, ctx.header(StripeWebhookParser.SIGNATURE_HEADER));
        Result result = paymentEvents.apply(notification);
        ctx.json(new WebhookResponse(true, result.name()));
    }
}
