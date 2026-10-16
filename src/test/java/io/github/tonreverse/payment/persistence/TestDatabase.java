package io.github.tonreverse.payment.persistence;

import static io.github.tonreverse.payment.persistence.jooq.Tables.FRAUD_FLAGS;
import static io.github.tonreverse.payment.persistence.jooq.Tables.IDEMPOTENCY_KEYS;
import static io.github.tonreverse.payment.persistence.jooq.Tables.ORDERS;
import static io.github.tonreverse.payment.persistence.jooq.Tables.ORDER_ITEMS;
import static io.github.tonreverse.payment.persistence.jooq.Tables.PAYMENT_EVENTS;
import static io.github.tonreverse.payment.persistence.jooq.Tables.PRODUCTS;

import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One real Postgres for the whole test run, set up exactly like production: same image as compose.yaml,
 * same {@link Database} factory methods, same Flyway migrations.
 *
 * <p>The container starts on first use and is shared by every integration test (starting one per test
 * class would add seconds each time). Testcontainers removes it when the JVM exits.
 */
public final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static DSLContext dsl;

    private TestDatabase() {}

    public static synchronized DSLContext dsl() {
        if (dsl == null) {
            POSTGRES.start();
            HikariDataSource dataSource = Database.createDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Database.migrate(dataSource);
            dsl = Database.createDsl(dataSource);
        }
        return dsl;
    }

    /** Removes what tests created. The seeded catalog stays; test products use ids starting with "test-". */
    public static void deleteTestData() {
        dsl().deleteFrom(PAYMENT_EVENTS).execute();
        dsl().deleteFrom(FRAUD_FLAGS).execute();
        dsl().deleteFrom(IDEMPOTENCY_KEYS).execute();
        dsl().deleteFrom(ORDER_ITEMS).execute();
        dsl().deleteFrom(ORDERS).execute();
        dsl().deleteFrom(PRODUCTS).where(PRODUCTS.ID.startsWith("test-")).execute();
    }
}
