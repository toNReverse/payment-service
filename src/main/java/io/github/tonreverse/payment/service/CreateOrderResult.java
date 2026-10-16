package io.github.tonreverse.payment.service;

import io.github.tonreverse.payment.domain.Order;

/** @param replayed true if this is the order created by an earlier request with the same Idempotency-Key */
public record CreateOrderResult(Order order, boolean replayed) {}
