package com.hunt.otziv.payments.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs post-commit reminder fan-out as one independent atomic unit. */
@Component
@RequiredArgsConstructor
public class PaymentIssueReminderTransactionExecutor {

    private final PlatformTransactionManager transactionManager;

    public void executeRequiresNew(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("Reminder transaction action is required");
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> action.run());
    }
}
