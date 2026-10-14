package io.github.tonreverse.payment.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** Database setup shared by App and the integration tests, so tests run against the production setup. */
public final class Database {

    private Database() {}

    public static HikariDataSource createDataSource(String jdbcUrl, String user, String password) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("payment-db");
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(user);
        hikari.setPassword(password);
        // Wait at most 2 s for a connection (Hikari's default is 30 s), so /health answers quickly.
        hikari.setConnectionTimeout(2_000);
        return new HikariDataSource(hikari);
    }

    /** Applies any migration in src/main/resources/db/migration that has not run yet. */
    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public static DSLContext createDsl(DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }
}
