package io.github.tonreverse.payment.health;

import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseHealthCheck implements HealthCheck {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthCheck.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 1;

    private final DataSource dataSource;

    public DatabaseHealthCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return "database";
    }

    /**
     * Borrows a pooled connection and asks the driver to validate it with a round trip to Postgres.
     * {@link Connection#isValid(int)} is the JDBC-standard way to do that and, unlike a bare
     * "SELECT 1", has a timeout built in.
     */
    @Override
    public boolean isHealthy() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            // SQLException, but also Hikari's unchecked exceptions when the pool cannot start.
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
