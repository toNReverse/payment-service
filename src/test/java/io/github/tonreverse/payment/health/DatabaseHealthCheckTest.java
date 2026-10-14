package io.github.tonreverse.payment.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

/**
 * Covers the failure path only: a real pool pointed at a port where nothing listens.
 * The happy path needs a real Postgres and is covered by the Testcontainers tests from Milestone 3.
 */
class DatabaseHealthCheckTest {

    @Test
    void isNamedDatabase() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            assertThat(new DatabaseHealthCheck(dataSource).name()).isEqualTo("database");
        }
    }

    @Test
    void reportsUnhealthyInsteadOfThrowingWhenTheDatabaseIsUnreachable() throws IOException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:" + closedPort() + "/payments");
        config.setConnectionTimeout(250);
        config.setInitializationFailTimeout(-1);

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            assertThat(new DatabaseHealthCheck(dataSource).isHealthy()).isFalse();
        }
    }

    /** Asks the OS for a free port and releases it, so nothing is listening there. */
    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
