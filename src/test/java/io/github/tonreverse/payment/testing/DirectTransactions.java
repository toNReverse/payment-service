package io.github.tonreverse.payment.testing;

import io.github.tonreverse.payment.persistence.Transactions;

/**
 * Runs the work with no transaction around it, so nothing is rolled back when it throws. Fine for logic
 * tests; rollback behaviour is covered against Postgres (JooqTransactionsTest, OrderIdempotencyIntegrationTest).
 */
public final class DirectTransactions implements Transactions {

    @Override
    public void inTransaction(Runnable work) {
        work.run();
    }
}
