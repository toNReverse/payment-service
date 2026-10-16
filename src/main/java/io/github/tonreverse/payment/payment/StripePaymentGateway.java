package io.github.tonreverse.payment.payment;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.ProductData;
import com.stripe.param.checkout.SessionCreateParams.PaymentIntentData;
import io.github.tonreverse.payment.config.AppConfig;
import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import java.time.Duration;
import java.util.Locale;

/**
 * Creates Stripe Checkout Sessions: a page hosted by Stripe where the customer pays. Card details never
 * reach this service. Whether the payment succeeded arrives later through webhooks (Milestone 6).
 */
public final class StripePaymentGateway implements PaymentGateway {

    // Stripe's SDK waits up to 80 s for a response by default. Shorter limits keep a whole call, retries
    // included, well inside the idempotency lock timeout (see WORST_CASE_CALL_DURATION).
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    // The SDK retries connection errors and 5xx answers, re-sending the same Idempotency-Key so Stripe
    // never creates two sessions for one attempt.
    static final int MAX_NETWORK_RETRIES = 2;
    /** Every attempt timing out, plus the SDK's backoff between attempts (well under 5 s for two retries). */
    public static final Duration WORST_CASE_CALL_DURATION =
            CONNECT_TIMEOUT.plus(READ_TIMEOUT).multipliedBy(MAX_NETWORK_RETRIES + 1).plusSeconds(5);

    private final StripeClient stripe;
    private final String successUrlTemplate;
    private final String cancelUrlTemplate;

    /** @param successUrlTemplate may contain {@value AppConfig#ORDER_ID_PLACEHOLDER}, replaced per order */
    public StripePaymentGateway(StripeClient stripe, String successUrlTemplate, String cancelUrlTemplate) {
        this.stripe = stripe;
        this.successUrlTemplate = successUrlTemplate;
        this.cancelUrlTemplate = cancelUrlTemplate;
    }

    /** The production client: real Stripe API, with the timeouts and retries above. */
    public static StripeClient client(String secretKey) {
        return StripeClient.builder()
                .setApiKey(secretKey)
                .setConnectTimeout((int) CONNECT_TIMEOUT.toMillis())
                .setReadTimeout((int) READ_TIMEOUT.toMillis())
                .setMaxNetworkRetries(MAX_NETWORK_RETRIES)
                .build();
    }

    @Override
    public CheckoutSession createCheckoutSession(Order order) {
        // Our order id is new on every attempt, so this only dedupes the SDK's own retries of this call.
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("checkout-session-" + order.id())
                .build();
        try {
            Session session = stripe.v1().checkout().sessions()
                    .create(checkoutParams(order, successUrlTemplate, cancelUrlTemplate), options);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            // Stripe's request id lets their support find the call; the message stays in our logs only.
            throw new PaymentGatewayException("Stripe could not create a checkout session for order " + order.id()
                    + " (HTTP " + e.getStatusCode() + ", request " + e.getRequestId() + "): " + e.getMessage(), e);
        }
    }

    /** Pure function from order to request, so tests can check it without calling Stripe. */
    static SessionCreateParams checkoutParams(Order order, String successUrlTemplate, String cancelUrlTemplate) {
        String orderId = order.id().toString();
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomerEmail(order.customerEmail())
                // Webhooks for the session carry these back, so they can be matched to the order.
                .setClientReferenceId(orderId)
                .putMetadata("order_id", orderId)
                // payment_intent.* events carry the PaymentIntent, which needs its own copy.
                .setPaymentIntentData(PaymentIntentData.builder().putMetadata("order_id", orderId).build())
                .setSuccessUrl(successUrlTemplate.replace(AppConfig.ORDER_ID_PLACEHOLDER, orderId))
                .setCancelUrl(cancelUrlTemplate.replace(AppConfig.ORDER_ID_PLACEHOLDER, orderId));

        // One line per order item, with the price we stored; Stripe adds them up to the same total.
        for (OrderItem item : order.items()) {
            params.addLineItem(LineItem.builder()
                    .setQuantity((long) item.quantity())
                    .setPriceData(PriceData.builder()
                            // Stripe amounts are in the currency's smallest unit, like Money (cents for EUR).
                            .setUnitAmount(item.unitPrice().amountMinor())
                            .setCurrency(item.unitPrice().currency().getCurrencyCode().toLowerCase(Locale.ROOT))
                            .setProductData(ProductData.builder()
                                    .setName(item.productName())
                                    .putMetadata("product_id", item.productId())
                                    .build())
                            .build())
                    .build());
        }
        return params.build();
    }
}
