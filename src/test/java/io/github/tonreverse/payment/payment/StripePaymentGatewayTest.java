package io.github.tonreverse.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.StripeClient;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.service.OrderService;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the gateway asks Stripe for, checked without any network call. */
class StripePaymentGatewayTest {

    private static final String SUCCESS = "https://shop.example/grazie?order={ORDER_ID}";
    private static final String CANCEL = "https://shop.example/carrello";

    private static final Order ORDER = Order.create(
            UUID.fromString("7d3f2a10-0000-4000-8000-000000000001"), "anna@example.com", List.of(
                    new OrderItem("pandoro", "Pandoro 1 kg", 2, Money.of(2_600, "EUR")),
                    new OrderItem("panettone-classico", "Panettone Classico 1 kg", 1, Money.of(2_800, "EUR"))),
            Instant.parse("2026-12-01T10:15:30Z"));

    private final SessionCreateParams params = StripePaymentGateway.checkoutParams(ORDER, SUCCESS, CANCEL);

    @Test
    void asksForAOneOffPaymentByTheCustomer() {
        assertThat(params.getMode()).isEqualTo(SessionCreateParams.Mode.PAYMENT);
        assertThat(params.getCustomerEmail()).isEqualTo("anna@example.com");
    }

    @Test
    void sendsOneLineItemPerOrderLineInMinorUnits() {
        assertThat(params.getLineItems()).hasSize(2);
        LineItem pandoro = params.getLineItems().getFirst();
        assertThat(pandoro.getQuantity()).isEqualTo(2L);
        assertThat(pandoro.getPriceData().getUnitAmount()).isEqualTo(2_600L);
        // Stripe expects lower-case ISO codes.
        assertThat(pandoro.getPriceData().getCurrency()).isEqualTo("eur");
        assertThat(pandoro.getPriceData().getProductData().getName()).isEqualTo("Pandoro 1 kg");
        assertThat(pandoro.getPriceData().getProductData().getMetadata()).containsEntry("product_id", "pandoro");
    }

    @Test
    void theLinesAddUpToTheOrderTotal() {
        // Stripe computes the total itself from the lines; it must be exactly what we stored.
        long stripeTotal = params.getLineItems().stream()
                .mapToLong(line -> line.getQuantity() * line.getPriceData().getUnitAmount())
                .sum();

        assertThat(stripeTotal).isEqualTo(ORDER.total().amountMinor());
    }

    @Test
    void tagsTheSessionAndThePaymentWithTheOrderIdForTheWebhooks() {
        String orderId = ORDER.id().toString();

        assertThat(params.getClientReferenceId()).isEqualTo(orderId);
        assertThat(params.getMetadata()).containsEntry("order_id", orderId);
        // payment_intent.* events carry the PaymentIntent, not the session, so it needs the id too.
        assertThat(params.getPaymentIntentData().getMetadata()).containsEntry("order_id", orderId);
    }

    @Test
    void fillsTheOrderIdIntoTheReturnUrls() {
        assertThat(params.getSuccessUrl()).isEqualTo("https://shop.example/grazie?order=" + ORDER.id());
        assertThat(params.getCancelUrl()).isEqualTo(CANCEL);
    }

    @Test
    void anUnreachableStripeBecomesAPaymentGatewayException() throws IOException {
        StripeClient unreachable = StripeClient.builder()
                .setApiKey("sk_test_unreachable")
                .setApiBase("http://localhost:" + closedPort())
                .setMaxNetworkRetries(0)
                .build();
        StripePaymentGateway gateway = new StripePaymentGateway(unreachable, SUCCESS, CANCEL);

        assertThatThrownBy(() -> gateway.createCheckoutSession(ORDER))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining(ORDER.id().toString());
    }

    @Test
    void aStripeCallAlwaysEndsBeforeTheIdempotencyLockCanBeTakenOver() {
        // Otherwise a request still waiting for Stripe could have its Idempotency-Key taken over.
        assertThat(StripePaymentGateway.WORST_CASE_CALL_DURATION).isLessThan(OrderService.DEFAULT_LOCK_TIMEOUT);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
