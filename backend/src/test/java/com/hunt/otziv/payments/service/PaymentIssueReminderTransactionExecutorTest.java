package com.hunt.otziv.payments.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentIssueReminderTransactionExecutorTest {

    @Test
    void recipientFanOutFailureRollsBackTheWholeRequiresNewTransaction() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        PaymentIssueReminderTransactionExecutor executor =
                new PaymentIssueReminderTransactionExecutor(transactionManager);

        assertThrows(
                IllegalStateException.class,
                () -> executor.executeRequiresNew(() -> {
                    throw new IllegalStateException("second reminder failed");
                })
        );

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(status);
    }
}
