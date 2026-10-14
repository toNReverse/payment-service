package io.github.tonreverse.payment.persistence;

import org.jooq.DSLContext;

/**
 * The DSLContext is built with jOOQ's ThreadLocalTransactionProvider (see {@link Database#createDsl}): while
 * the work runs, the transaction's connection is bound to the current thread, and every repository using
 * that same DSLContext on this thread joins the transaction. A Javalin request runs on one thread, so this
 * is safe as long as the work does not hand database calls to other threads.
 */
public final class JooqTransactions implements Transactions {

    private final DSLContext dsl;

    public JooqTransactions(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void inTransaction(Runnable work) {
        dsl.transaction(work::run);
    }
}
