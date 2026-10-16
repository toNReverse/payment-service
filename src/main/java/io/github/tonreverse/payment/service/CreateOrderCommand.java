package io.github.tonreverse.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * A validated request to place an order. Prices are not part of it: they always come from the catalog.
 * The API layer has already checked the format (email, product id slugs, quantity range).
 */
public record CreateOrderCommand(String customerEmail, List<Line> lines) {

    public record Line(String productId, int quantity) {}

    public CreateOrderCommand {
        Objects.requireNonNull(customerEmail, "customerEmail");
        lines = List.copyOf(lines);
    }

    /**
     * Identifies what was asked for, to tell a genuine retry from an Idempotency-Key reused for a
     * different order. Lines are sorted first, so listing the same products in another order is the
     * same request. Product ids are slugs ([a-z0-9-]), so the ':' and newline separators are unambiguous.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder(customerEmail);
        lines.stream()
                .sorted(Comparator.comparing(Line::productId))
                .forEach(line -> canonical.append('\n').append(line.productId()).append(':').append(line.quantity()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM must support SHA-256", e);
        }
    }
}
