package io.github.tonreverse.payment.service;

import io.github.tonreverse.payment.domain.CheckoutSession;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderItem;
import io.github.tonreverse.payment.domain.Product;
import io.github.tonreverse.payment.payment.PaymentGateway;
import io.github.tonreverse.payment.persistence.IdempotencyKeyRepository;
import io.github.tonreverse.payment.persistence.IdempotencyRecord;
import io.github.tonreverse.payment.persistence.IdempotencyRecord.State;
import io.github.tonreverse.payment.persistence.OrderRepository;
import io.github.tonreverse.payment.persistence.ProductRepository;
import io.github.tonreverse.payment.persistence.Transactions;
import io.github.tonreverse.payment.service.CreateOrderCommand.Line;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places orders exactly once per Idempotency-Key. The full design is in docs/decisions.md; in short:
 *
 * <ol>
 *   <li>Reserve the key (one atomic INSERT). If it exists: replay its order, reject a different request
 *       with the same key, or report that the first request is still running.
 *   <li>Holding the key, price the items from the catalog and create the provider's checkout session.
 *       This happens outside any database transaction, so no connection waits on the network.
 *   <li>Save the order and complete the key in one transaction. Completing is fenced by the reservation
 *       timestamp, so a request whose reservation was taken over cannot also save an order.
 *   <li>On any failure before that, release the key, so the client can retry with it.
 * </ol>
 */
public final class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Longer than the slowest successful request (the provider call), or live requests would be taken over. */
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_RESERVATION_ATTEMPTS = 3;

    private sealed interface Reservation {
        /** This request now holds the key, with this timestamp as its fencing token. */
        record Held(Instant lockedAt) implements Reservation {}

        /** An earlier request with the same key and content already created this order. */
        record Replay(UUID orderId) implements Reservation {}
    }

    private final ProductRepository products;
    private final OrderRepository orders;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final Transactions transactions;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final Supplier<UUID> newOrderId;
    private final Duration lockTimeout;

    public OrderService(ProductRepository products, OrderRepository orders, IdempotencyKeyRepository idempotencyKeys,
            Transactions transactions, PaymentGateway paymentGateway, Clock clock, Supplier<UUID> newOrderId,
            Duration lockTimeout) {
        this.products = products;
        this.orders = orders;
        this.idempotencyKeys = idempotencyKeys;
        this.transactions = transactions;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
        this.newOrderId = newOrderId;
        this.lockTimeout = lockTimeout;
    }

    /**
     * @throws ValidationException if a product is unknown, not for sale, or priced in another currency
     * @throws IdempotencyKeyReusedException if the key was used for a different request
     * @throws IdempotencyKeyInUseException if another request with the key is still running
     * @throws io.github.tonreverse.payment.payment.PaymentGatewayException if the provider fails
     */
    public CreateOrderResult createOrder(String idempotencyKey, CreateOrderCommand command) {
        return switch (reserve(idempotencyKey, command.fingerprint())) {
            case Reservation.Replay(UUID orderId) -> new CreateOrderResult(loadOrderCreatedEarlier(orderId), true);
            case Reservation.Held(Instant lockedAt) -> new CreateOrderResult(createHolding(idempotencyKey, lockedAt, command), false);
        };
    }

    /** @throws NotFoundException if there is no such order */
    public Order getOrder(UUID id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("order " + id + " does not exist"));
    }

    private Reservation reserve(String key, String requestHash) {
        for (int attempt = 1; attempt <= MAX_RESERVATION_ATTEMPTS; attempt++) {
            Instant now = now();
            if (idempotencyKeys.tryReserve(key, requestHash, now)) {
                return new Reservation.Held(now);
            }

            Optional<IdempotencyRecord> found = idempotencyKeys.find(key);
            if (found.isEmpty()) {
                continue; // released between our insert and our read: try to reserve again
            }
            IdempotencyRecord existing = found.get();
            if (!existing.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException(key);
            }
            if (existing.state() == State.COMPLETED) {
                return new Reservation.Replay(existing.orderId());
            }

            // Still IN_PROGRESS. Either its request is running right now, or it crashed after reserving.
            boolean abandoned = existing.lockedAt().plus(lockTimeout).isBefore(now);
            if (abandoned && idempotencyKeys.tryTakeOver(key, existing.lockedAt(), now)) {
                log.warn("Taking over Idempotency-Key {}, reserved at {} and never completed", key, existing.lockedAt());
                return new Reservation.Held(now);
            }
            throw new IdempotencyKeyInUseException(key);
        }
        throw new IdempotencyKeyInUseException(key);
    }

    private Order createHolding(String key, Instant lockedAt, CreateOrderCommand command) {
        try {
            Order order = Order.create(newOrderId.get(), command.customerEmail(), priceFromCatalog(command), now());
            CheckoutSession session = paymentGateway.createCheckoutSession(order);
            Order pending = order.startCheckout(session, now());

            transactions.inTransaction(() -> {
                orders.insert(pending);
                if (!idempotencyKeys.complete(key, lockedAt, pending.id())) {
                    // We were so slow that another request took the key over. Throwing rolls back our
                    // order, so the key ends up with a single order: the other request's.
                    throw new IdempotencyKeyInUseException(key);
                }
            });
            log.info("Created order {} for Idempotency-Key {}", pending.id(), key);
            return pending;
        } catch (RuntimeException e) {
            releaseAfterFailure(key, lockedAt, e);
            throw e;
        }
    }

    /**
     * Nothing was saved, so the key can be given back. If even that fails (database down), the reservation
     * stays until the lock timeout, after which a retry takes it over.
     */
    private void releaseAfterFailure(String key, Instant lockedAt, RuntimeException failure) {
        try {
            idempotencyKeys.release(key, lockedAt);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private List<OrderItem> priceFromCatalog(CreateOrderCommand command) {
        List<String> productIds = command.lines().stream().map(Line::productId).toList();
        Map<String, Product> catalog = products.findByIds(productIds).stream()
                .collect(Collectors.toMap(Product::id, Function.identity()));

        List<String> problems = new ArrayList<>();
        List<OrderItem> items = new ArrayList<>();
        for (Line line : command.lines()) {
            Product product = catalog.get(line.productId());
            if (product == null || !product.active()) {
                problems.add("product '" + line.productId() + "' is not for sale");
            } else {
                items.add(new OrderItem(product.id(), line.quantity(), product.price()));
            }
        }
        if (items.stream().map(item -> item.unitPrice().currency()).distinct().count() > 1) {
            problems.add("all products in an order must be priced in the same currency");
        }
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return items;
    }

    private Order loadOrderCreatedEarlier(UUID orderId) {
        // The foreign key on idempotency_keys.order_id guarantees the order exists.
        return orders.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Idempotency-Key points to missing order " + orderId));
    }

    /** Postgres stores microseconds; truncating here keeps saved and in-memory timestamps equal. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
