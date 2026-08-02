package com.hunt.otziv.client_messages.service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.UnexpectedRollbackException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientMessageTransactionRunnerTest {

    @Test
    void rollbackOnlyStateDoesNotPoisonFollowingStateTransaction() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfiguration.class)) {
            ClientMessageTransactionRunner runner = context.getBean(ClientMessageTransactionRunner.class);
            RollbackOnlyParticipant participant = context.getBean(RollbackOnlyParticipant.class);
            List<String> committedStates = new ArrayList<>();

            assertThrows(UnexpectedRollbackException.class, () ->
                    runner.runInNewTransaction(() -> {
                        try {
                            participant.failInJoinedTransaction();
                        } catch (IllegalStateException ignored) {
                            // Matches the scheduler path where the business failure is handled,
                            // but Spring has already marked the shared state transaction rollback-only.
                        }
                        afterCommit(() -> committedStates.add("poison"));
                    })
            );

            runner.runInNewTransaction(() -> afterCommit(() -> committedStates.add("healthy")));

            assertEquals(List.of("healthy"), committedStates);
        }
    }

    private static void afterCommit(Runnable callback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionTestConfiguration {

        @Bean
        DataSource dataSource() throws Exception {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenAnswer(invocation -> connection());
            return dataSource;
        }

        private Connection connection() throws Exception {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            return connection;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ClientMessageTransactionRunner transactionRunner(PlatformTransactionManager transactionManager) {
            return new ClientMessageTransactionRunner(transactionManager);
        }

        @Bean
        RollbackOnlyParticipant rollbackOnlyParticipant() {
            return new RollbackOnlyParticipant();
        }
    }

    static class RollbackOnlyParticipant {

        @Transactional
        public void failInJoinedTransaction() {
            throw new IllegalStateException("joined transaction failed");
        }
    }
}
