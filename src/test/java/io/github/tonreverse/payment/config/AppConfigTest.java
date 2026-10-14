package io.github.tonreverse.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    private static Map<String, String> validEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("PORT", "9090");
        env.put("DATABASE_URL", "jdbc:postgresql://localhost:5432/payments");
        env.put("DATABASE_USER", "payments");
        env.put("DATABASE_PASSWORD", "s3cret");
        env.put("REDIS_URL", "redis://localhost:6379");
        return env;
    }

    @Test
    void readsAllValuesFromTheEnvironment() {
        AppConfig config = AppConfig.from(validEnv());

        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.databaseUrl()).isEqualTo("jdbc:postgresql://localhost:5432/payments");
        assertThat(config.databaseUser()).isEqualTo("payments");
        assertThat(config.databasePassword()).isEqualTo("s3cret");
        assertThat(config.redisUrl()).isEqualTo("redis://localhost:6379");
    }

    @Test
    void portDefaultsTo8080WhenNotSet() {
        Map<String, String> env = validEnv();
        env.remove("PORT");

        assertThat(AppConfig.from(env).port()).isEqualTo(8080);
    }

    @Test
    void failsFastListingEveryMissingVariable() {
        assertThatThrownBy(() -> AppConfig.from(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("DATABASE_URL", "DATABASE_USER", "DATABASE_PASSWORD", "REDIS_URL");
    }

    @Test
    void treatsBlankValuesAsMissing() {
        Map<String, String> env = validEnv();
        env.put("REDIS_URL", "  ");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_URL");
    }

    @Test
    void rejectsNonNumericPort() {
        Map<String, String> env = validEnv();
        env.put("PORT", "eighty");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PORT");
    }

    @Test
    void rejectsPortOutOfRange() {
        Map<String, String> env = validEnv();
        env.put("PORT", "70000");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PORT");
    }

    @Test
    void toStringDoesNotLeakThePassword() {
        assertThat(AppConfig.from(validEnv()).toString()).doesNotContain("s3cret");
    }

    @Test
    void toStringDoesNotLeakCredentialsInTheRedisUrl() {
        Map<String, String> env = validEnv();
        env.put("REDIS_URL", "redis://default:redis-pass@cache.internal:6379");

        assertThat(AppConfig.from(env).toString())
                .doesNotContain("redis-pass")
                .contains("cache.internal:6379");
    }
}
