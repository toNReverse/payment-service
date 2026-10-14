package io.github.tonreverse.payment.health;

/**
 * One dependency the service needs (database, Redis, ...).
 *
 * <p>Implementations must answer quickly and must not throw: any failure, including a timeout, is
 * reported as {@code false}. The controller can then build a response without caring how each check
 * fails.
 */
public interface HealthCheck {

    /** Key used in the /health response, e.g. "database". */
    String name();

    boolean isHealthy();
}
