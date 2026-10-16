package io.github.tonreverse.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.domain.Money;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.domain.OrderStatus;
import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.payment.PaymentGatewayException;
import io.github.tonreverse.payment.persistence.IdempotencyRecord.State;
import io.github.tonreverse.payment.service.CreateOrderCommand.Line;
import io.github.tonreverse.payment.testing.DirectTransactions;
import io.github.tonreverse.payment.testing.InMemoryIdempotencyKeyRepository;
import io.github.tonreverse.payment.testing.InMemoryOrderRepository;
import io.github.tonreverse.payment.testing.InMemoryProductRepository;
import io.github.tonreverse.payment.testing.StubPaymentGateway;
import io.github.tonreverse.payment.testing.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Order creation and idempotency logic, with in-memory fakes. The same scenarios against Postgres are in
 * OrderIdempotencyIntegrationTest. */
class OrderServiceTest {

    private static final Instant START = Instant.parse("2026-12-01T10:15:30.123456Z");
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(60);
    private static final String KEY = "c0ffee00-key-1";

    private static final Product PANDORO = new Product("pandoro", "Pandoro 1 kg", Money.of(2_600, "EUR"), true);
    private static final Product CLASSICO =
            new Product("panettone-classico", "Panettone Classico 1 kg", Money.of(2_800, "EUR"), true);
    private static final Product LIMONCELLO =
            new Product("panettone-limoncello", "Panettone al Limoncello 1 kg", Money.of(3_500, "EUR"), false);
    private static final Product IMPORTED =
            new Product("panettone-usa", "Panettone (US import)", Money.of(4_000, "USD"), true);

    private final InMemoryOrderRepository orders = new InMemoryOrderRepository();
    private final InMemoryIdempotencyKeyRepository keys = new InMemoryIdempotencyKeyRepository();
    private final StubPaymentGateway gateway = new StubPaymentGateway();
    private final TestClock clock = new TestClock(START);
    private final OrderService service = new OrderService(
            new InMemoryProductRepository(PANDORO, CLASSICO, LIMONCELLO, IMPORTED),
            orders, keys, new DirectTransactions(), gateway, clock, UUID::randomUUID, LOCK_TIMEOUT);

    private static CreateOrderCommand twoPandoroOneClassico() {
        return new CreateOrderCommand("anna@example.com", List.of(new Line("pandoro", 2), new Line("panettone-classico", 1)));
    }

    @Nested
    class FirstRequest {

        @Test
        void createsAPendingOrderPricedFromTheCatalog() {
            CreateOrderResult result = service.createOrder(KEY, twoPandoroOneClassico());

            Order order = result.order();
            assertThat(result.replayed()).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.items()).containsExactly(
                    new OrderItem("pandoro", "Pandoro 1 kg", 2, Money.of(2_600, "EUR")),
                    new OrderItem("panettone-classico", "Panettone Classico 1 kg", 1, Money.of(2_800, "EUR")));
            assertThat(order.total()).isEqualTo(Money.of(8_000, "EUR"));
            assertThat(order.checkoutSession()).isEqualTo(StubPaymentGateway.sessionFor(order));
            assertThat(orders.findById(order.id())).hasValue(order);
            assertThat(keys.find(KEY)).hasValueSatisfying(record -> {
                assertThat(record.state()).isEqualTo(State.COMPLETED);
                assertThat(record.orderId()).isEqualTo(order.id());
            });
        }

        @Test
        void storesTimestampsWithMicrosecondPrecisionLikePostgres() {
            clock.advance(Duration.ofNanos(789));

            Order order = service.createOrder(KEY, twoPandoroOneClassico()).order();

            assertThat(order.createdAt().getNano() % 1_000).isZero();
        }
    }

    @Nested
    class Replays {

        @Test
        void theSameKeyAndRequestReturnTheOriginalOrderWithoutChargingAgain() {
            Order first = service.createOrder(KEY, twoPandoroOneClassico()).order();

            CreateOrderResult second = service.createOrder(KEY, twoPandoroOneClassico());

            assertThat(second.replayed()).isTrue();
            assertThat(second.order()).isEqualTo(first);
            assertThat(gateway.calls()).isEqualTo(1);
            assertThat(orders.count()).isEqualTo(1);
        }

        @Test
        void listingTheSameLinesInAnotherOrderIsTheSameRequest() {
            Order first = service.createOrder(KEY, twoPandoroOneClassico()).order();
            CreateOrderCommand reordered = new CreateOrderCommand(
                    "anna@example.com", List.of(new Line("panettone-classico", 1), new Line("pandoro", 2)));

            assertThat(service.createOrder(KEY, reordered).order().id()).isEqualTo(first.id());
        }

        @Test
        void theSameKeyWithADifferentRequestIsRejected() {
            service.createOrder(KEY, twoPandoroOneClassico());
            CreateOrderCommand different = new CreateOrderCommand("anna@example.com", List.of(new Line("pandoro", 5)));

            assertThatThrownBy(() -> service.createOrder(KEY, different))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
            assertThat(gateway.calls()).isEqualTo(1);
            assertThat(orders.count()).isEqualTo(1);
        }
    }

    @Nested
    class ConcurrentAndCrashedRequests {

        @Test
        void aKeyHeldByARequestStillInProgressIsRejected() {
            keys.tryReserve(KEY, twoPandoroOneClassico().fingerprint(), START);

            assertThatThrownBy(() -> service.createOrder(KEY, twoPandoroOneClassico()))
                    .isInstanceOf(IdempotencyKeyInUseException.class);
            assertThat(gateway.calls()).isZero();
        }

        @Test
        void aReservationYoungerThanTheLockTimeoutIsRespected() {
            keys.tryReserve(KEY, twoPandoroOneClassico().fingerprint(), START);
            clock.advance(LOCK_TIMEOUT.minusSeconds(1));

            assertThatThrownBy(() -> service.createOrder(KEY, twoPandoroOneClassico()))
                    .isInstanceOf(IdempotencyKeyInUseException.class);
        }

        @Test
        void aReservationAbandonedLongerThanTheLockTimeoutIsTakenOver() {
            // Its holder crashed between reserving the key and saving the order.
            keys.tryReserve(KEY, twoPandoroOneClassico().fingerprint(), START);
            clock.advance(LOCK_TIMEOUT.plusSeconds(1));

            CreateOrderResult result = service.createOrder(KEY, twoPandoroOneClassico());

            assertThat(result.replayed()).isFalse();
            assertThat(keys.find(KEY)).hasValueSatisfying(record -> assertThat(record.state()).isEqualTo(State.COMPLETED));
        }

        @Test
        void aRequestThatLosesItsReservationWhileWaitingForTheProviderFails() {
            Instant takenOverAt = START.plus(LOCK_TIMEOUT).plusSeconds(1);
            // While the provider is slow, another request decides the reservation was abandoned and takes it.
            gateway.duringCall(order -> keys.tryTakeOver(KEY, START, takenOverAt));

            assertThatThrownBy(() -> service.createOrder(KEY, twoPandoroOneClassico()))
                    .isInstanceOf(IdempotencyKeyInUseException.class);
            // The key belongs to the other request: we neither completed nor released it.
            assertThat(keys.find(KEY)).hasValueSatisfying(record -> {
                assertThat(record.state()).isEqualTo(State.IN_PROGRESS);
                assertThat(record.lockedAt()).isEqualTo(takenOverAt);
            });
        }
    }

    @Nested
    class Failures {

        @Test
        void aPaymentProviderFailureReleasesTheKeySoTheClientCanRetryWithIt() {
            PaymentGatewayException outage = new PaymentGatewayException("Stripe is unreachable");
            gateway.failWith(outage);

            assertThatThrownBy(() -> service.createOrder(KEY, twoPandoroOneClassico())).isSameAs(outage);
            assertThat(keys.find(KEY)).isEmpty();
            assertThat(orders.count()).isZero();

            gateway.succeed();
            CreateOrderResult retry = service.createOrder(KEY, twoPandoroOneClassico());
            assertThat(retry.replayed()).isFalse();
            assertThat(orders.count()).isEqualTo(1);
        }

        @Test
        void unknownAndInactiveProductsAreAllReportedAndTheKeyIsReleased() {
            CreateOrderCommand command = new CreateOrderCommand("anna@example.com", List.of(
                    new Line("panettone-mango", 1), new Line("panettone-limoncello", 1), new Line("pandoro", 1)));

            assertThatThrownBy(() -> service.createOrder(KEY, command))
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.details())
                            .containsExactly("product 'panettone-mango' is not for sale",
                                    "product 'panettone-limoncello' is not for sale"));
            assertThat(keys.find(KEY)).isEmpty();
            assertThat(gateway.calls()).isZero();
        }

        @Test
        void productsPricedInDifferentCurrenciesCannotBeOrderedTogether() {
            CreateOrderCommand command = new CreateOrderCommand(
                    "anna@example.com", List.of(new Line("pandoro", 1), new Line("panettone-usa", 1)));

            assertThatThrownBy(() -> service.createOrder(KEY, command))
                    .isInstanceOfSatisfying(ValidationException.class,
                            e -> assertThat(e.details()).anySatisfy(detail -> assertThat(detail).contains("currency")));
        }
    }

    @Nested
    class Lookup {

        @Test
        void returnsASavedOrder() {
            Order created = service.createOrder(KEY, twoPandoroOneClassico()).order();

            assertThat(service.getOrder(created.id())).isEqualTo(created);
        }

        @Test
        void anUnknownIdIsNotFound() {
            assertThatThrownBy(() -> service.getOrder(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        }
    }
}
