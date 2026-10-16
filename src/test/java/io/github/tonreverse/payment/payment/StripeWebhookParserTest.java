package io.github.tonreverse.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.payment.PaymentUpdate.AwaitingPayment;
import io.github.tonreverse.payment.payment.PaymentUpdate.CheckoutExpired;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentAttemptFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentFailed;
import io.github.tonreverse.payment.payment.PaymentUpdate.PaymentSucceeded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Refunded;
import io.github.tonreverse.payment.payment.PaymentUpdate.Unrelated;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StripeWebhookParserTest {

    private static final String SECRET = "whsec_test_endpoint_secret";
    private static final Instant NOW = Instant.parse("2026-12-01T10:15:30Z");
    private static final UUID ORDER_ID = UUID.fromString("7d3f2a10-0000-4000-8000-000000000001");

    private final StripeWebhookParser parser = new StripeWebhookParser(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * Stripe's scheme, written out independently of the SDK so the test checks it rather than trusts it:
     * header "t=<unix seconds>,v1=<hex HMAC-SHA256 of '<t>.<raw body>' keyed with the endpoint secret>".
     */
    static String sign(String payload, String secret, Instant at) {
        try {
            long timestamp = at.getEpochSecond();
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = hmac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String event(String id, String type, String object) {
        return """
                {"id": "%s", "object": "event", "type": "%s", "livemode": false, "data": {"object": %s}}
                """.formatted(id, type, object);
    }

    private PaymentUpdate updateFor(String type, String object) {
        String payload = event("evt_test_1", type, object);
        return parser.parse(payload, sign(payload, SECRET, NOW)).update();
    }

    @Nested
    class Signature {

        private final String payload = event("evt_test_1", "checkout.session.expired", """
                {"id": "cs_test_1", "metadata": {"order_id": "%s"}}""".formatted(ORDER_ID));

        @Test
        void acceptsAnEventSignedWithTheEndpointSecret() {
            PaymentNotification notification = parser.parse(payload, sign(payload, SECRET, NOW));

            assertThat(notification.eventId()).isEqualTo("evt_test_1");
            assertThat(notification.eventType()).isEqualTo("checkout.session.expired");
            assertThat(notification.payload()).isEqualTo(payload);
        }

        @Test
        void rejectsABodyChangedAfterSigning() {
            String header = sign(payload, SECRET, NOW);
            String tampered = payload.replace("cs_test_1", "cs_test_2");

            assertThatThrownBy(() -> parser.parse(tampered, header)).isInstanceOf(InvalidWebhookException.class);
        }

        @Test
        void rejectsASignatureMadeWithAnotherSecret() {
            assertThatThrownBy(() -> parser.parse(payload, sign(payload, "whsec_someone_else", NOW)))
                    .isInstanceOf(InvalidWebhookException.class);
        }

        @Test
        void rejectsARequestWithoutTheHeader() {
            assertThatThrownBy(() -> parser.parse(payload, null))
                    .isInstanceOf(InvalidWebhookException.class)
                    .hasMessageContaining("Stripe-Signature");
        }

        @Test
        void rejectsASignatureOlderThanFiveMinutesSoACapturedRequestCannotBeReplayed() {
            String oldHeader = sign(payload, SECRET, NOW.minusSeconds(301));

            assertThatThrownBy(() -> parser.parse(payload, oldHeader)).isInstanceOf(InvalidWebhookException.class);
        }

        @Test
        void acceptsTheRequestIfAnyOfSeveralSignaturesMatches() {
            // While an endpoint secret is being rolled, Stripe signs with the old and the new one.
            String other = sign(payload, "whsec_old_secret", NOW).split(",")[1];
            String header = sign(payload, SECRET, NOW) + "," + other;

            assertThat(parser.parse(payload, header).eventId()).isEqualTo("evt_test_1");
        }

        @Test
        void aCorrectlySignedBodyMustStillBeAStripeEvent() {
            String notAnEvent = "{\"hello\": \"world\"}";

            assertThatThrownBy(() -> parser.parse(notAnEvent, sign(notAnEvent, SECRET, NOW)))
                    .isInstanceOf(InvalidWebhookException.class);
        }
    }

    @Nested
    class CheckoutSessions {

        @Test
        void aCompletedAndPaidSessionIsAPayment() {
            PaymentUpdate update = updateFor("checkout.session.completed", """
                    {"id": "cs_test_1", "client_reference_id": "%s", "metadata": {"order_id": "%s"},
                     "payment_status": "paid", "amount_total": 8000, "currency": "eur"}""".formatted(ORDER_ID, ORDER_ID));

            assertThat(update).isEqualTo(new PaymentSucceeded(ORDER_ID, "cs_test_1", Money.of(8_000, "EUR")));
        }

        @Test
        void aCompletedButUnpaidSessionStillWaitsForTheMoney() {
            // Delayed methods such as SEPA Direct Debit: the money is confirmed later by async_payment_succeeded.
            PaymentUpdate update = updateFor("checkout.session.completed", """
                    {"id": "cs_test_1", "metadata": {"order_id": "%s"},
                     "payment_status": "unpaid", "amount_total": 8000, "currency": "eur"}""".formatted(ORDER_ID));

            assertThat(update).isEqualTo(new AwaitingPayment(ORDER_ID, "cs_test_1"));
        }

        @Test
        void anAsyncPaymentThatSucceededIsAPayment() {
            PaymentUpdate update = updateFor("checkout.session.async_payment_succeeded", """
                    {"id": "cs_test_1", "metadata": {"order_id": "%s"},
                     "payment_status": "paid", "amount_total": 8000, "currency": "eur"}""".formatted(ORDER_ID));

            assertThat(update).isEqualTo(new PaymentSucceeded(ORDER_ID, "cs_test_1", Money.of(8_000, "EUR")));
        }

        @Test
        void anAsyncPaymentThatFailedIsAFinalFailure() {
            PaymentUpdate update = updateFor("checkout.session.async_payment_failed", """
                    {"id": "cs_test_1", "metadata": {"order_id": "%s"}}""".formatted(ORDER_ID));

            assertThat(update).isEqualTo(new PaymentFailed(ORDER_ID, "cs_test_1"));
        }

        @Test
        void anExpiredSessionIsAnAbandonedCheckout() {
            PaymentUpdate update = updateFor("checkout.session.expired", """
                    {"id": "cs_test_1", "metadata": {"order_id": "%s"}}""".formatted(ORDER_ID));

            assertThat(update).isEqualTo(new CheckoutExpired(ORDER_ID, "cs_test_1"));
        }

        @Test
        void theClientReferenceIdIsUsedWhenMetadataIsMissing() {
            PaymentUpdate update = updateFor("checkout.session.expired", """
                    {"id": "cs_test_1", "client_reference_id": "%s"}""".formatted(ORDER_ID));

            assertThat(update.orderId()).isEqualTo(ORDER_ID);
        }
    }

    @Nested
    class PaymentsAndRefunds {

        @Test
        void aFailedCardAttemptKeepsTheReason() {
            PaymentUpdate update = updateFor("payment_intent.payment_failed", """
                    {"id": "pi_test_1", "metadata": {"order_id": "%s"},
                     "last_payment_error": {"message": "Your card was declined."}}""".formatted(ORDER_ID));

            assertThat(update).isEqualTo(new PaymentAttemptFailed(ORDER_ID, "Your card was declined."));
        }

        @Test
        void aFullyRefundedCharge() {
            PaymentUpdate update = updateFor("charge.refunded", """
                    {"id": "ch_test_1", "metadata": {"order_id": "%s"},
                     "refunded": true, "amount_refunded": 8000, "currency": "eur"}""".formatted(ORDER_ID));

            assertThat(update).isEqualTo(new Refunded(ORDER_ID, true, Money.of(8_000, "EUR")));
        }

        @Test
        void aPartiallyRefundedCharge() {
            PaymentUpdate update = updateFor("charge.refunded", """
                    {"id": "ch_test_1", "metadata": {"order_id": "%s"},
                     "refunded": false, "amount_refunded": 2600, "currency": "eur"}""".formatted(ORDER_ID));

            assertThat(update).isEqualTo(new Refunded(ORDER_ID, false, Money.of(2_600, "EUR")));
        }
    }

    @Nested
    class NotOurs {

        @Test
        void eventTypesWeDoNotHandle() {
            PaymentUpdate update = updateFor("customer.created", "{\"id\": \"cus_test_1\"}");

            assertThat(update).isInstanceOf(Unrelated.class);
            assertThat(update.orderId()).isNull();
        }

        @Test
        void eventsWithoutAnOrderId() {
            // For example sessions created by `stripe trigger` or by another integration on the account.
            PaymentUpdate update = updateFor("checkout.session.expired", "{\"id\": \"cs_test_1\", \"metadata\": {}}");

            assertThat(update).isInstanceOf(Unrelated.class);
        }

        @Test
        void eventsWhoseOrderIdIsNotOneOfOurs() {
            PaymentUpdate update = updateFor("checkout.session.expired",
                    "{\"id\": \"cs_test_1\", \"metadata\": {\"order_id\": \"wp-order-1234\"}}");

            assertThat(update).isInstanceOf(Unrelated.class);
        }
    }
}
