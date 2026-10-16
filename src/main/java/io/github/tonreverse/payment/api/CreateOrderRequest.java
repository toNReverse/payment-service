package io.github.tonreverse.payment.api;

import io.github.tonreverse.payment.service.CreateOrderCommand;
import io.github.tonreverse.payment.service.CreateOrderCommand.Line;
import io.github.tonreverse.payment.service.ValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The JSON body of POST /orders, exactly as the client sent it. Fields are nullable wrapper types, so a
 * missing field can be told apart from a zero. {@link #toCommand()} checks the format and reports every
 * problem at once; whether the products exist is the service's job.
 */
record CreateOrderRequest(String customerEmail, List<Item> items) {

    record Item(String productId, Integer quantity) {}

    static final int MAX_ITEMS = 50;
    static final int MAX_QUANTITY = 1_000;
    // Deliberately loose: real validation of an address is sending mail to it (Stripe does, on payment).
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern PRODUCT_ID = Pattern.compile("[a-z0-9-]{1,64}");

    CreateOrderCommand toCommand() {
        List<String> problems = new ArrayList<>();
        String email = normalizedEmail(problems);
        List<Line> lines = lines(problems);
        if (!problems.isEmpty()) {
            throw new ValidationException(problems);
        }
        return new CreateOrderCommand(email, lines);
    }

    private String normalizedEmail(List<String> problems) {
        if (customerEmail == null || customerEmail.isBlank()) {
            problems.add("customerEmail is required");
            return null;
        }
        // Lower-cased so the same customer is recognized however they type it (fraud rules count per email).
        String email = customerEmail.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            problems.add("customerEmail must be a valid email address");
        }
        return email;
    }

    private List<Line> lines(List<String> problems) {
        if (items == null || items.isEmpty()) {
            problems.add("items must contain at least one item");
            return List.of();
        }
        if (items.size() > MAX_ITEMS) {
            problems.add("items can contain at most " + MAX_ITEMS + " entries");
            return List.of();
        }

        List<Line> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String at = "items[" + i + "]";
            Item item = items.get(i);
            if (item == null) {
                problems.add(at + " is required");
                continue;
            }
            int problemsBefore = problems.size();

            if (item.productId() == null) {
                problems.add(at + ".productId is required");
            } else if (!PRODUCT_ID.matcher(item.productId()).matches()) {
                problems.add(at + ".productId must be 1-64 lowercase letters, digits or dashes");
            } else if (!seen.add(item.productId())) {
                problems.add(at + ".productId '" + item.productId() + "' is listed more than once");
            }

            if (item.quantity() == null) {
                problems.add(at + ".quantity is required");
            } else if (item.quantity() < 1 || item.quantity() > MAX_QUANTITY) {
                problems.add(at + ".quantity must be between 1 and " + MAX_QUANTITY);
            }

            if (problems.size() == problemsBefore) {
                lines.add(new Line(item.productId(), item.quantity()));
            }
        }
        return lines;
    }
}
