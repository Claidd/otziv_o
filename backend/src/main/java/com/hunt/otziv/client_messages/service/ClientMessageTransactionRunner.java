package com.hunt.otziv.client_messages.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ClientMessageTransactionRunner {

    private final TransactionTemplate requiresNew;
    private final TransactionTemplate preparation;

    public ClientMessageTransactionRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.preparation = new TransactionTemplate(transactionManager);
        this.preparation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.preparation.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T callInNewTransaction(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }

    /** Budget reads made after a contended reservation lock must see the preceding commit. */
    public <T> T callInPreparationTransaction(Supplier<T> work) {
        return preparation.execute(status -> work.get());
    }

    public void runInNewTransaction(Runnable work) {
        requiresNew.executeWithoutResult(status -> work.run());
    }
}
