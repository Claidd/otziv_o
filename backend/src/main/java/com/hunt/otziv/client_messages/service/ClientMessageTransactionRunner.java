package com.hunt.otziv.client_messages.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ClientMessageTransactionRunner {

    private final TransactionTemplate requiresNew;

    public ClientMessageTransactionRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T callInNewTransaction(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }

    public void runInNewTransaction(Runnable work) {
        requiresNew.executeWithoutResult(status -> work.run());
    }
}
