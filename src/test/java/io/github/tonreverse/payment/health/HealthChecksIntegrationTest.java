package io.github.tonreverse.payment.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tonreverse.payment.persistence.TestDatabase;
import io.github.tonreverse.payment.testing.TestRedis;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The success path of the health checks, against a real Postgres and Redis (the unit tests cover failures). */
@Tag("integration")
class HealthChecksIntegrationTest {

    @Test
    void theDatabaseCheckPassesWhenPostgresAnswers() {
        assertThat(new DatabaseHealthCheck(TestDatabase.dataSource()).isHealthy()).isTrue();
    }

    @Test
    void theRedisCheckPassesWhenRedisAnswersPing() {
        assertThat(new RedisHealthCheck(TestRedis.client()).isHealthy()).isTrue();
    }
}
