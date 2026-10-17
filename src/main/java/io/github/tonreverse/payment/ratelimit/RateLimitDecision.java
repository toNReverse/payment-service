package io.github.tonreverse.payment.ratelimit;

/** @param retryAfterSeconds when refused, how long until the window resets (for the Retry-After header) */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    static final RateLimitDecision ALLOW = new RateLimitDecision(true, 0);
}
