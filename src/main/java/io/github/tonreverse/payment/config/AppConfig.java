package io.github.tonreverse.payment.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * All configuration, read once at startup from environment variables.
 *
 * <p>{@link #from(Map)} takes the environment as a plain map instead of calling {@code System.getenv()}
 * itself, so tests can pass any environment they want. Validation collects every problem before failing,
 * so a misconfigured deployment reports all missing variables in one go instead of one per restart.
 *
 * @param stripeSecretKey a Stripe test-mode secret key, or null to use the fake payment gateway
 * @param checkoutSuccessUrl where Stripe sends the customer after paying; {@value #ORDER_ID_PLACEHOLDER}
 *     is replaced with the order id
 * @param checkoutCancelUrl where Stripe sends the customer who leaves the checkout page
 */
public record AppConfig(
        int port,
        String databaseUrl,
        String databaseUser,
        String databasePassword,
        String redisUrl,
        String stripeSecretKey,
        String checkoutSuccessUrl,
        String checkoutCancelUrl) {

    public static final String ORDER_ID_PLACEHOLDER = "{ORDER_ID}";
    static final int DEFAULT_PORT = 8080;

    public static AppConfig from(Map<String, String> env) {
        List<String> errors = new ArrayList<>();

        int port = parsePort(env.get("PORT"), errors);
        String databaseUrl = required(env, "DATABASE_URL", errors);
        String databaseUser = required(env, "DATABASE_USER", errors);
        String databasePassword = required(env, "DATABASE_PASSWORD", errors);
        String redisUrl = required(env, "REDIS_URL", errors);
        String stripeSecretKey = stripeTestKey(env.get("STRIPE_SECRET_KEY"), errors);
        // Without a shop frontend, the customer lands on this service's JSON view of the order.
        String orderPage = "http://localhost:" + port + "/orders/" + ORDER_ID_PLACEHOLDER;
        String checkoutSuccessUrl = httpUrl(env, "CHECKOUT_SUCCESS_URL", orderPage, errors);
        String checkoutCancelUrl = httpUrl(env, "CHECKOUT_CANCEL_URL", orderPage, errors);

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid configuration: " + String.join("; ", errors));
        }
        return new AppConfig(port, databaseUrl, databaseUser, databasePassword, redisUrl,
                stripeSecretKey, checkoutSuccessUrl, checkoutCancelUrl);
    }

    public boolean usesStripe() {
        return stripeSecretKey != null;
    }

    private static String required(Map<String, String> env, String name, List<String> errors) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            errors.add(name + " is not set");
            return null;
        }
        return value.strip();
    }

    private static int parsePort(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(value.strip());
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // reported below, together with the out-of-range case
        }
        errors.add("PORT must be a number between 1 and 65535, got '" + value + "'");
        return DEFAULT_PORT;
    }

    /**
     * Optional, but if set it must be a test-mode secret key (sk_test_) or restricted key (rk_test_): this
     * service never touches real money, so a live key is always a mistake and stops the startup. The
     * error never repeats the value, because it ends up in logs.
     */
    private static String stripeTestKey(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String key = value.strip();
        if (!key.startsWith("sk_test_") && !key.startsWith("rk_test_")) {
            errors.add("STRIPE_SECRET_KEY must be a test-mode secret key (sk_test_... or rk_test_...)");
        }
        return key;
    }

    private static String httpUrl(Map<String, String> env, String name, String defaultValue, List<String> errors) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String url = value.strip();
        try {
            // The placeholder's braces are not legal in a URI, so check the URL with a sample id in their place.
            URI uri = new URI(url.replace(ORDER_ID_PLACEHOLDER, "order-id"));
            boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            if (http && uri.getHost() != null) {
                return url;
            }
        } catch (URISyntaxException ignored) {
            // reported below
        }
        errors.add(name + " must be an absolute http(s) URL");
        return null;
    }

    /** The generated record toString would print the secrets, and configs end up in logs. */
    @Override
    public String toString() {
        return ("AppConfig[port=%d, databaseUrl=%s, databaseUser=%s, databasePassword=****, redisUrl=%s, "
                + "stripeSecretKey=%s, checkoutSuccessUrl=%s, checkoutCancelUrl=%s]")
                .formatted(port, databaseUrl, databaseUser, maskUserInfo(redisUrl),
                        stripeSecretKey == null ? "not set (fake gateway)" : stripeSecretKey.substring(0, 8) + "****",
                        checkoutSuccessUrl, checkoutCancelUrl);
    }

    /** redis://user:password@host:6379 becomes redis://****@host:6379. */
    private static String maskUserInfo(String url) {
        try {
            String userInfo = new URI(url).getRawUserInfo();
            return userInfo == null ? url : url.replace(userInfo + "@", "****@");
        } catch (URISyntaxException e) {
            return "****";
        }
    }
}
