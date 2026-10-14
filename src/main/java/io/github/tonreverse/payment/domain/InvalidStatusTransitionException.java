package io.github.tonreverse.payment.domain;

import java.io.Serial;

public final class InvalidStatusTransitionException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final OrderStatus from;
    private final OrderStatus to;

    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot change order status from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
