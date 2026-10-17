package io.github.tonreverse.payment.domain;

import java.util.Set;

/**
 * Order lifecycle as an explicit state machine:
 *
 * <pre>
 * CREATED ──► PENDING_PAYMENT ──► PAID ──► REFUNDED
 *    │              ├──► FAILED
 *    │              ├──► EXPIRED
 *    └──────────────┴──► FLAGGED (suspected fraud)
 * </pre>
 *
 * Every status change goes through {@link #transitionTo}, so no code path can put an order into a
 * status the table does not allow (for example, a late webhook moving a REFUNDED order back to PAID).
 */
public enum OrderStatus {
    CREATED,
    PENDING_PAYMENT,
    PAID,
    FAILED,
    EXPIRED,
    REFUNDED,
    FLAGGED;

    /**
     * The transition table. The switch has no default branch, so adding a status without deciding its
     * transitions is a compile error rather than a silent "no transitions allowed".
     */
    private Set<OrderStatus> nextStatuses() {
        return switch (this) {
            case CREATED -> Set.of(PENDING_PAYMENT, FLAGGED);
            case PENDING_PAYMENT -> Set.of(PAID, FAILED, EXPIRED, FLAGGED);
            case PAID -> Set.of(REFUNDED);
            case FAILED, EXPIRED, REFUNDED, FLAGGED -> Set.of();
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return nextStatuses().contains(next);
    }

    /**
     * @return {@code next}, so callers can write {@code status = status.transitionTo(PAID)}
     * @throws InvalidStatusTransitionException if the table does not allow the change
     */
    public OrderStatus transitionTo(OrderStatus next) {
        if (!canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(this, next);
        }
        return next;
    }

    /** No further status change is possible. */
    public boolean isTerminal() {
        return nextStatuses().isEmpty();
    }

    /**
     * Whether {@code target} can still be reached through one or more allowed transitions. Webhooks use
     * it to tell an event that arrived too early (a refund before the payment: retry later) from one that
     * no longer applies (an expiry after the payment: ignore). The table has no cycles, so a status never
     * reaches itself.
     */
    public boolean canEventuallyReach(OrderStatus target) {
        for (OrderStatus next : nextStatuses()) {
            if (next == target || next.canEventuallyReach(target)) {
                return true;
            }
        }
        return false;
    }
}
