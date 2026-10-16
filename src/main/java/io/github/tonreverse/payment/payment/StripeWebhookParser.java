package io.github.tonreverse.payment.payment;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.payment.PaymentUpdate.AwaitingPayment;
import io.github.tonreverse.payment.payment.PaymentUpdate.CheckoutExpired;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentAttemptFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentSucceeded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Refunded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Unrelated;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a Stripe webhook request into a {@link PaymentNotification}: first proves it comes from Stripe,
 * then reads the few fields we need.
 *
 * <p>The JSON is read with Jackson rather than the SDK's Event classes. Those only deserialize events
 * whose API version matches the SDK's pinned version and quietly return nothing otherwise, while the
 * fields used here are stable across versions.
 */
public final class StripeWebhookParser {

    public static final String SIGNATURE_HEADER = "Stripe-Signature";
    /** Stripe's recommended limit: a signature older than this is refused, so a captured request cannot be replayed. */
    static final long TOLERANCE_SECONDS = 300;

    private final String webhookSecret;
    private final Clock clock;

    /** @param webhookSecret the endpoint's signing secret (whsec_...), from the Dashboard or the Stripe CLI */
    public StripeWebhookParser(String webhookSecret, Clock clock) {
        this.webhookSecret = webhookSecret;
        this.clock = clock;
    }

    /**
     * @param payload the request body exactly as received: the signature covers these bytes, so it must not
     *     be parsed and re-serialized first
     * @throws InvalidWebhookException if the request was not signed by Stripe with our secret
     */
    public PaymentNotification parse(String payload, String signatureHeader) {
        verifySignature(payload, signatureHeader);

        JsonNode event;
        try {
            event = JsonMapper.shared().readTree(payload);
        } catch (JacksonException e) {
            throw new InvalidWebhookException("the body is not JSON");
        }
        String id = text(event, "id");
        String type = text(event, "type");
        JsonNode object = event.path("data").path("object");
        if (id == null || type == null || !object.isObject()) {
            throw new InvalidWebhookException("the body is not a Stripe event");
        }
        return new PaymentNotification(id, type, payload, translate(type, object));
    }

    /**
     * HMAC-SHA256 of "timestamp.payload" with the endpoint secret, compared in constant time, plus the
     * timestamp check, done by the SDK. A header may carry several signatures while a secret is rolled.
     */
    private void verifySignature(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookException("missing " + SIGNATURE_HEADER + " header");
        }
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader, webhookSecret, TOLERANCE_SECONDS, clock);
        } catch (SignatureVerificationException e) {
            throw new InvalidWebhookException("signature verification failed");
        }
    }

    private static PaymentUpdate translate(String type, JsonNode object) {
        return switch (type) {
            case "checkout.session.completed" -> "paid".equals(text(object, "payment_status"))
                    ? paymentSucceeded(object)
                    : forSession(object, AwaitingPayment::new);
            case "checkout.session.async_payment_succeeded" -> paymentSucceeded(object);
            case "checkout.session.async_payment_failed" -> forSession(object, PaymentFailed::new);
            case "checkout.session.expired" -> forSession(object, CheckoutExpired::new);
            case "payment_intent.payment_failed" -> paymentAttemptFailed(object);
            // The charge inherits the PaymentIntent's metadata, including our order id.
            case "charge.refunded" -> refunded(object);
            default -> new Unrelated("event type " + type + " is not handled");
        };
    }

    private static PaymentUpdate paymentSucceeded(JsonNode session) {
        UUID orderId = orderId(session);
        if (orderId == null) {
            return noOrder();
        }
        return new PaymentSucceeded(orderId, text(session, "id"), money(session, "amount_total"));
    }

    private static PaymentUpdate forSession(JsonNode session, BiFunction<UUID, String, PaymentUpdate> update) {
        UUID orderId = orderId(session);
        return orderId == null ? noOrder() : update.apply(orderId, text(session, "id"));
    }

    private static PaymentUpdate paymentAttemptFailed(JsonNode paymentIntent) {
        UUID orderId = orderId(paymentIntent);
        if (orderId == null) {
            return noOrder();
        }
        String reason = text(paymentIntent.path("last_payment_error"), "message");
        return new PaymentAttemptFailed(orderId, reason == null ? "no reason given" : reason);
    }

    private static PaymentUpdate refunded(JsonNode charge) {
        UUID orderId = orderId(charge);
        if (orderId == null) {
            return noOrder();
        }
        return new Refunded(orderId, charge.path("refunded").asBoolean(false), money(charge, "amount_refunded"));
    }

    /** From our metadata, or the session's client_reference_id. Anything that is not a UUID is not ours. */
    private static UUID orderId(JsonNode object) {
        String value = text(object.path("metadata"), "order_id");
        if (value == null) {
            value = text(object, "client_reference_id");
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Unrelated noOrder() {
        return new Unrelated("the event does not reference one of our orders");
    }

    /** Stripe amounts are in the currency's smallest unit with a lower-case code, e.g. 8000 + "eur". */
    private static Money money(JsonNode object, String amountField) {
        JsonNode amount = object.path(amountField);
        String currency = text(object, "currency");
        if (!amount.isIntegralNumber() || currency == null) {
            throw new IllegalStateException("Stripe event without " + amountField + " and currency");
        }
        return Money.of(amount.longValue(), currency.toUpperCase(Locale.ROOT));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }
}
