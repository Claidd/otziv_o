package com.hunt.otziv.common_billing.service;

import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class CommonInvoiceAfterCommitSenderTest {
    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void rollbackNeverSendsButCommitSendsOnceAfterBusinessWork() {
        var events = new ArrayList<Object>();
        var sender = new CommonInvoiceAfterCommitSender(events::add);
        TransactionSynchronizationManager.initSynchronization();
        sender.send(42L,false);
        assertThat(events).isEmpty();
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(events).isEmpty();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        sender.send(42L,false);
        assertThat(events).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(events).containsExactly(new CommonInvoiceAfterCommitSender.Request(42L,false));
    }

    @Test void directSendPreservesFailureAndPostCommitCannotUndoCommittedMoney() {
        var sender = new CommonInvoiceAfterCommitSender(event -> { throw new IllegalStateException("provider unavailable"); });
        assertThatThrownBy(() -> sender.send(42L,false)).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.initSynchronization();
        sender.send(42L,false);
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
    }
}
