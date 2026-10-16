package io.github.tonreverse.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.StripeClient;
import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real Stripe SDK making real HTTP calls, against stripe-mock: Stripe's official mock server, which
 * validates every request against Stripe's API specification and answers with sample objects. It needs
 * no account or key, so it runs in CI. A wrong or misspelled parameter fails here with a 400.
 */
@Tag("integration")
class StripePaymentGatewayIntegrationTest {

    private static final GenericContainer<?> STRIPE_MOCK =
            new GenericContainer<>(DockerImageName.parse("stripe/stripe-mock:v0.206.0")).withExposedPorts(12111);

    private static StripePaymentGateway gateway;

    @BeforeAll
    static void startStripeMock() {
        STRIPE_MOCK.start();
        StripeClient client = StripeClient.builder()
                .setApiKey("sk_test_123") // stripe-mock accepts any key that looks like a test key
                .setApiBase("http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(12111))
                .build();
        gateway = new StripePaymentGateway(client, "https://shop.example/grazie?order={ORDER_ID}", "https://shop.example/carrello");
    }

    @Test
    void createsACheckoutSessionWithTheParametersStripeExpects() {
        Order order = Order.create(UUID.randomUUID(), "anna@example.com", List.of(
                new OrderItem("pandoro", "Pandoro 1 kg", 2, Money.of(2_600, "EUR")),
                new OrderItem("panettone-classico", "Panettone Classico 1 kg", 1, Money.of(2_800, "EUR"))),
                Instant.now());

        CheckoutSession session = gateway.createCheckoutSession(order);

        assertThat(session.id()).startsWith("cs_");
        assertThat(session.url()).startsWith("https://");
    }
}
