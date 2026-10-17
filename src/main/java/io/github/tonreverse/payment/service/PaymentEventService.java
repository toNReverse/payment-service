package io.github.tonreverse.payment.service;

import io.github.tonreverse.payment.domain.FraudFlag;
import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.domain.OrderStatus;
import io.github.tonreverse.payment.payment.PaymentNotification;
import io.github.tonreverse.payment.persistence.FraudFlagRepository;
import io.github.tonreverse.payment.persistence.OrderRepository;
import io.github.tonreverse.payment.persistence.PaymentEventRecord;
import io.github.tonreverse.payment.persistence.PaymentEventRecord.Outcome;
import io.github.tonreverse.payment.persistence.PaymentEventRepository;
import io.github.tonreverse.payment.persistence.Transactions;
import io.github.tonreverse.payment.service.PaymentEventDecisions.ChangeStatus;
import io.github.tonreverse.payment.service.PaymentEventDecisions.Decision;
import io.github.tonreverse.payment.service.PaymentEventDecisions.Flag;
import io.github.tonreverse.payment.service.PaymentEventDecisions.Ignore;
import io.github.tonreverse.payment.service.PaymentEventDecisions.RecordOnly;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies verified webhook events to orders, each exactly once and in its own transaction:
 *
 * <ol>
 *   <li>lock the order row (SELECT ... FOR UPDATE), so events for one order are handled one at a time;
 *   <li>if the event id is already stored, it is a redelivery: acknowledge, change nothing;
 *   <li>decide what the event means ({@link PaymentEventDecisions});
 *   <li>store the event and apply the decision. Both commit together or not at all.
 * </ol>
 *
 * An event that arrives too early throws {@link PaymentEventOutOfOrderException} before anything is stored.
 */
public final class PaymentEventService {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventService.class);
    private static final int MAX_NOTE_LENGTH = 500;

    public enum Result { APPLIED, FLAGGED, RECORDED, IGNORED, DUPLICATE }

    private final OrderRepository orders;
    private final PaymentEventRepository events;
    private final FraudFlagRepository fraudFlags;
    private final Transactions transactions;
    private final Clock clock;
    private final Supplier<UUID> newFlagId;

    public PaymentEventService(OrderRepository orders, PaymentEventRepository events, FraudFlagRepository fraudFlags,
            Transactions transactions, Clock clock, Supplier<UUID> newFlagId) {
        this.orders = orders;
        this.events = events;
        this.fraudFlags = fraudFlags;
        this.transactions = transactions;
        this.clock = clock;
        this.newFlagId = newFlagId;
    }

    /** @throws PaymentEventOutOfOrderException if the event depends on one that has not arrived yet */
    public Result apply(PaymentNotification notification) {
        return transactions.inTransactionReturning(() -> applyInTransaction(notification));
    }

    private Result applyInTransaction(PaymentNotification notification) {
        UUID orderId = notification.update().orderId();
        // Lock first. Two deliveries for the same order now queue here, so the second sees the first's result.
        Optional<Order> order = orderId == null ? Optional.empty() : orders.findByIdForUpdate(orderId);

        if (events.exists(notification.eventId())) {
            log.info("Stripe event {} ({}) was already processed", notification.eventId(), notification.eventType());
            return Result.DUPLICATE;
        }

        Decision decision = PaymentEventDecisions.decide(order.orElse(null), notification.update());
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Outcome outcome = outcomeOf(decision);
        String note = truncate(noteOf(decision));

        PaymentEventRecord record = new PaymentEventRecord(notification.eventId(), notification.eventType(),
                order.map(Order::id).orElse(null), outcome, note, notification.payload(), now);
        if (!events.tryInsert(record)) {
            // A concurrent delivery of the same event, for no order (so no lock), stored it first.
            return Result.DUPLICATE;
        }

        switch (decision) {
            case ChangeStatus change -> orders.updateStatus(order.orElseThrow().transitionTo(change.to(), now));
            case Flag flag -> {
                orders.updateStatus(order.orElseThrow().transitionTo(OrderStatus.FLAGGED, now));
                fraudFlags.insert(new FraudFlag(newFlagId.get(), orderId, flag.rule(), flag.reason(), now));
                log.warn("Order {} flagged by Stripe event {}: {}", orderId, notification.eventId(), flag.reason());
            }
            case RecordOnly recordOnly -> { }
            case Ignore ignore -> { }
        }
        log.info("Stripe event {} ({}) for order {}: {}, {}",
                notification.eventId(), notification.eventType(), orderId, outcome, note);
        return Result.valueOf(outcome.name());
    }

    private static Outcome outcomeOf(Decision decision) {
        return switch (decision) {
            case ChangeStatus change -> Outcome.APPLIED;
            case Flag flag -> Outcome.FLAGGED;
            case RecordOnly recordOnly -> Outcome.RECORDED;
            case Ignore ignore -> Outcome.IGNORED;
        };
    }

    private static String noteOf(Decision decision) {
        return switch (decision) {
            case ChangeStatus change -> change.note();
            case Flag flag -> flag.rule() + ": " + flag.reason();
            case RecordOnly recordOnly -> recordOnly.note();
            case Ignore ignore -> ignore.note();
        };
    }

    private static String truncate(String note) {
        return note.length() <= MAX_NOTE_LENGTH ? note : note.substring(0, MAX_NOTE_LENGTH - 1) + "…";
    }
}
