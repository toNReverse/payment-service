package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.ratelimit.FixedWindowRateLimiter;
import io.github.tonreverse.payment.ratelimit.RateLimitDecision;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;

/**
 * Runs before POST /orders and refuses clients that place too many orders (429). It runs before any
 * parsing or validation, so invalid requests count too and cannot be used to probe the API for free.
 *
 * <p>The client is the TCP peer address ({@code ctx.ip()}). Behind a load balancer every request would
 * come from the balancer, and the real client would be in X-Forwarded-For; that header must only be
 * trusted from known proxies, or anyone could pick their own IP. This service has no proxy in front.
 */
public final class OrderRateLimitFilter {

    private final FixedWindowRateLimiter limiter;

    public OrderRateLimitFilter(FixedWindowRateLimiter limiter) {
        this.limiter = limiter;
    }

    public void handle(Context ctx) {
        if (ctx.method() != HandlerType.POST) {
            return;
        }
        RateLimitDecision decision = limiter.check(ctx.ip());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }
    }
}
