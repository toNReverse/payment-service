package io.github.tonreverse.payment.config;

import java.time.Duration;

/** At most {@code maxRequests} per client IP in each {@code window}. Requests beyond that get a 429. */
public record RateLimitSettings(int maxRequests, Duration window) {}
