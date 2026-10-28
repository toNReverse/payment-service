package io.github.tonreverse.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tonreverse.payment.domain.Money;
import java.time.Duration;
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
    void withoutAStripeKeyTheFakeGatewayIsUsed() {
        AppConfig config = AppConfig.from(validEnv());

        assertThat(config.stripeSecretKey()).isNull();
        assertThat(config.usesStripe()).isFalse();
    }

    @Test
    void acceptsStripeTestModeSecretAndRestrictedKeys() {
        for (String key : new String[] {"sk_test_51abcDEF", "rk_test_51abcDEF"}) {
            Map<String, String> env = validEnv();
            env.put("STRIPE_SECRET_KEY", key);

            AppConfig config = AppConfig.from(env);

            assertThat(config.stripeSecretKey()).isEqualTo(key);
            assertThat(config.usesStripe()).isTrue();
        }
    }

    @Test
    void refusesLiveKeysWithoutRepeatingThemInTheError() {
        // This service only ever talks to Stripe in test mode, so a live key is always a mistake.
        Map<String, String> env = validEnv();
        env.put("STRIPE_SECRET_KEY", "sk_live_51liveSECRETvalue");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-mode")
                .hasMessageNotContaining("liveSECRETvalue");
    }

    @Test
    void refusesAPublishableKeyInPlaceOfTheSecretKey() {
        Map<String, String> env = validEnv();
        env.put("STRIPE_SECRET_KEY", "pk_test_51abcDEF");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE_SECRET_KEY");
    }

    @Test
    void checkoutReturnUrlsDefaultToTheOrderOnThisService() {
        AppConfig config = AppConfig.from(validEnv());

        assertThat(config.checkoutSuccessUrl()).isEqualTo("http://localhost:9090/orders/{ORDER_ID}");
        assertThat(config.checkoutCancelUrl()).isEqualTo("http://localhost:9090/orders/{ORDER_ID}");
    }

    @Test
    void readsCheckoutReturnUrls() {
        Map<String, String> env = validEnv();
        env.put("CHECKOUT_SUCCESS_URL", "https://shop.example/grazie?order={ORDER_ID}");
        env.put("CHECKOUT_CANCEL_URL", "https://shop.example/carrello");

        AppConfig config = AppConfig.from(env);

        assertThat(config.checkoutSuccessUrl()).isEqualTo("https://shop.example/grazie?order={ORDER_ID}");
        assertThat(config.checkoutCancelUrl()).isEqualTo("https://shop.example/carrello");
    }

    @Test
    void checkoutReturnUrlsMustBeAbsoluteHttpUrls() {
        Map<String, String> env = validEnv();
        env.put("CHECKOUT_SUCCESS_URL", "/grazie");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHECKOUT_SUCCESS_URL");
    }

    @Test
    void toStringShowsOnlyTheModeOfTheStripeKey() {
        Map<String, String> env = validEnv();
        env.put("STRIPE_SECRET_KEY", "sk_test_51abcDEFsecretPART");

        assertThat(AppConfig.from(env).toString()).contains("sk_test_****").doesNotContain("secretPART");
    }

    @Test
    void theWebhookSecretIsOptional() {
        AppConfig config = AppConfig.from(validEnv());

        assertThat(config.stripeWebhookSecret()).isNull();
        assertThat(config.receivesStripeWebhooks()).isFalse();
    }

    @Test
    void readsTheWebhookSigningSecret() {
        Map<String, String> env = validEnv();
        env.put("STRIPE_WEBHOOK_SECRET", "whsec_abc123");

        AppConfig config = AppConfig.from(env);

        assertThat(config.stripeWebhookSecret()).isEqualTo("whsec_abc123");
        assertThat(config.receivesStripeWebhooks()).isTrue();
    }

    @Test
    void refusesSomethingThatIsNotAWebhookSecretWithoutRepeatingIt() {
        // A classic mix-up: pasting the API key where the webhook signing secret belongs.
        Map<String, String> env = validEnv();
        env.put("STRIPE_WEBHOOK_SECRET", "sk_test_51abcSECRET");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE_WEBHOOK_SECRET")
                .hasMessageNotContaining("abcSECRET");
    }

    @Test
    void toStringHidesTheWebhookSecret() {
        Map<String, String> env = validEnv();
        env.put("STRIPE_WEBHOOK_SECRET", "whsec_abcSECRET");

        assertThat(AppConfig.from(env).toString()).contains("whsec_****").doesNotContain("abcSECRET");
    }

    @Test
    void fraudAndRateLimitSettingsHaveDefaults() {
        AppConfig config = AppConfig.from(validEnv());

        assertThat(config.fraud()).isEqualTo(new FraudSettings(Money.of(50_000, "EUR"), 20, 3, Duration.ofMinutes(60)));
        assertThat(config.orderRateLimit()).isEqualTo(new RateLimitSettings(10, Duration.ofMinutes(1)));
    }

    @Test
    void readsFraudAndRateLimitSettings() {
        Map<String, String> env = validEnv();
        env.put("FRAUD_MAX_ORDER_TOTAL_CENTS", "30000");
        env.put("FRAUD_MAX_QUANTITY_PER_PRODUCT", "12");
        env.put("FRAUD_MAX_ORDERS_PER_EMAIL", "5");
        env.put("FRAUD_ORDERS_PER_EMAIL_WINDOW_MINUTES", "30");
        env.put("RATE_LIMIT_ORDERS_PER_MINUTE", "4");

        AppConfig config = AppConfig.from(env);

        assertThat(config.fraud()).isEqualTo(new FraudSettings(Money.of(30_000, "EUR"), 12, 5, Duration.ofMinutes(30)));
        assertThat(config.orderRateLimit()).isEqualTo(new RateLimitSettings(4, Duration.ofMinutes(1)));
    }

    @Test
    void limitsMustBePositiveWholeNumbers() {
        Map<String, String> env = validEnv();
        env.put("FRAUD_MAX_QUANTITY_PER_PRODUCT", "0");
        env.put("RATE_LIMIT_ORDERS_PER_MINUTE", "ten");

        assertThatThrownBy(() -> AppConfig.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll("FRAUD_MAX_QUANTITY_PER_PRODUCT", "RATE_LIMIT_ORDERS_PER_MINUTE");
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
