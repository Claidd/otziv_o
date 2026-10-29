package com.hunt.otziv.manager_control.service;

import static org.mockito.Mockito.*;
import com.hunt.otziv.client_messages.api.ClientReminderQueueRestored;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

class ManagerControlReminderRecoverySyncTest {
    @org.springframework.boot.test.context.TestConfiguration @EnableTransactionManagement
    static class Fixture {
        @Bean ManagerControlBoardWorkflow board() { return mock(ManagerControlBoardWorkflow.class); }
        @Bean com.hunt.otziv.client_messages.api.PeriodicReminderRestart recoveries() {
            return mock(com.hunt.otziv.client_messages.api.PeriodicReminderRestart.class);
        }
        @Bean ManagerControlReminderRecoverySync sync(org.springframework.beans.factory.ObjectProvider<ManagerControlBoardWorkflow> board,
                org.springframework.beans.factory.ObjectProvider<com.hunt.otziv.client_messages.api.PeriodicReminderRestart> recoveries) {
            return new ManagerControlReminderRecoverySync(board, recoveries);
        }
        @Bean PlatformTransactionManager transactions() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { }
                @Override protected void doRollback(DefaultTransactionStatus status) { }
            };
        }
    }

    @Test void onlyCommittedRecoveriesCloseCardsAndRepeatedEventsCoalesceAcrossMidnight() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var sync = context.getBean(ManagerControlReminderRecoverySync.class);
            var board = context.getBean(ManagerControlBoardWorkflow.class);
            var day = LocalDate.of(2026,10,29);
            tx.executeWithoutResult(status -> {
                context.publishEvent(new ClientReminderQueueRestored(day));
                sync.synchronize(); verifyNoInteractions(board);
                status.setRollbackOnly();
            });
            sync.synchronize(); verifyNoInteractions(board);
            tx.executeWithoutResult(status -> {
                context.publishEvent(new ClientReminderQueueRestored(day));
                context.publishEvent(new ClientReminderQueueRestored(day));
                context.publishEvent(new ClientReminderQueueRestored(day.plusDays(1)));
                sync.synchronize(); verifyNoInteractions(board);
            });
            sync.synchronize(); sync.synchronize();
            verify(board).synchronizeDailySnapshot(day);
            verify(board).synchronizeDailySnapshot(day.plusDays(1));
            verify(board).synchronizeDailySnapshot(day.minusDays(1));
            verifyNoMoreInteractions(board);
        }
    }

    @Test void failedSynchronizationIsRetriedWithoutLosingTheCommittedRecovery() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var sync = context.getBean(ManagerControlReminderRecoverySync.class);
            var board = context.getBean(ManagerControlBoardWorkflow.class);
            var day = LocalDate.of(2026,10,29);
            doThrow(new IllegalStateException("fixture failure")).doNothing().when(board).synchronizeDailySnapshot(day);
            tx.executeWithoutResult(status -> context.publishEvent(new ClientReminderQueueRestored(day)));
            sync.synchronize(); sync.synchronize(); sync.synchronize();
            verify(board,times(2)).synchronizeDailySnapshot(day);
        }
    }

    @Test void restartRestoresSynchronizationFromTheCommittedJournalWithoutAnInMemoryEvent() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var source = context.getBean(com.hunt.otziv.client_messages.api.PeriodicReminderRestart.class);
            when(source.reminderRecoveryGeneration()).thenReturn(32L);
            var sync = context.getBean(ManagerControlReminderRecoverySync.class);
            var board = context.getBean(ManagerControlBoardWorkflow.class);
            var date = LocalDate.now(java.time.ZoneId.of("Asia/Irkutsk"));
            sync.synchronize(); sync.synchronize();
            verify(board).synchronizeDailySnapshot(date); verify(board).synchronizeDailySnapshot(date.minusDays(1));
            verifyNoMoreInteractions(board);
        }
    }
}
