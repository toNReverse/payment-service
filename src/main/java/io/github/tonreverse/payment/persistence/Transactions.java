package io.github.tonreverse.payment.persistence;

import java.util.function.Supplier;

/**
 * Runs several repository calls as one database transaction, without the caller knowing about jOOQ.
 * Services depend on this interface; tests can pass an implementation that just runs the work.
 */
public interface Transactions {

    /** Commits if {@code work} returns, rolls back and rethrows if it throws. */
    void inTransaction(Runnable work);

    /** Same, for work that produces a result. */
    <T> T inTransactionReturning(Supplier<T> work);
}
