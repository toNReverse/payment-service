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
 */
public record AppConfig(int port, String databaseUrl, String databaseUser, String databasePassword, String redisUrl) {

    static final int DEFAULT_PORT = 8080;

    public static AppConfig from(Map<String, String> env) {
        List<String> errors = new ArrayList<>();

        int port = parsePort(env.get("PORT"), errors);
        String databaseUrl = required(env, "DATABASE_URL", errors);
        String databaseUser = required(env, "DATABASE_USER", errors);
        String databasePassword = required(env, "DATABASE_PASSWORD", errors);
        String redisUrl = required(env, "REDIS_URL", errors);

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid configuration: " + String.join("; ", errors));
        }
        return new AppConfig(port, databaseUrl, databaseUser, databasePassword, redisUrl);
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

    /** The generated record toString would print the secrets, and configs end up in logs. */
    @Override
    public String toString() {
        return "AppConfig[port=%d, databaseUrl=%s, databaseUser=%s, databasePassword=****, redisUrl=%s]"
                .formatted(port, databaseUrl, databaseUser, maskUserInfo(redisUrl));
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
