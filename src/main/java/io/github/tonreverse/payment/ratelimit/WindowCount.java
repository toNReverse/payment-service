package io.github.tonreverse.payment.ratelimit;

import java.time.Instant;

/** How many events a key has in the current window, and when that window ends (and the count resets). */
public record WindowCount(long count, Instant windowEnds) {}
