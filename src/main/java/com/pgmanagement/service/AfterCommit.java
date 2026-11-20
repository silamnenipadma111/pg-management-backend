package com.pgmanagement.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs side effects (emails, SMS) only once the surrounding database transaction has committed. */
final class AfterCommit {

    private AfterCommit() {
    }

    /** Runs the action after the current transaction commits, or immediately when there is no transaction. */
    static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
