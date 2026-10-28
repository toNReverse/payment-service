package io.github.tonreverse.payment.fraud;

import io.github.tonreverse.payment.domain.Order;
import io.github.tonreverse.payment.ratelimit.WindowCounter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Flags the next order once an email has placed {@code maxOrders} in the current window: many orders in
 * a short time, often each with a different card, is how stolen cards get tested. Counted in Redis with
 * the same fixed windows as the rate limiter.
 *
 * <p>Every evaluated order counts, flagged or not, including ones whose payment page later fails. The
 * counter is a separate store from Postgres, so it cannot be rolled back with the order; over-counting
 * slightly is the safe direction for a fraud signal.
 */
public final class OrdersPerEmailRule implements FraudRule {

    private final WindowCounter counter;
    private final int maxOrders;
    private final Duration window;

    public OrdersPerEmailRule(WindowCounter counter, int maxOrders, Duration window) {
        this.counter = counter;
        this.maxOrders = maxOrders;
        this.window = window;
    }

    @Override
    public String name() {
        return "orders_per_email_above_limit";
    }

    @Override
    public Optional<String> check(Order order) {
        long count = counter.increment("fraud:orders-per-email:" + sha256(order.customerEmail()), window).count();
        if (count <= maxOrders) {
            return Optional.empty();
        }
        return Optional.of(count + " orders from this email within " + window.toMinutes()
                + " minutes, above the limit of " + maxOrders);
    }

    /** The key identifies the customer for counting without putting their email address in Redis. */
    private static String sha256(String email) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM must support SHA-256", e);
        }
    }
}
