package com.hunt.otziv.p_products.service;

import com.hunt.otziv.p_products.dto.OrderPaidPostCommitEvent;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaidPostCommitListenerTransactionTest {

    @Test
    void dispatchesOnlyAfterCommitAndNeverFromRollbackOnlyPayment() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfiguration.class)) {
            PaymentEventProbe probe = context.getBean(PaymentEventProbe.class);
            OrderPaidPostCommitEffects effects = context.getBean(OrderPaidPostCommitEffects.class);

            probe.publishAndCommit(101L);
            verify(effects).apply(101L);

            reset(effects);
            assertThrows(UnexpectedRollbackException.class, () ->
                    probe.publishAfterCaughtJoinedFailure(102L)
            );
            verify(effects, never()).apply(102L);
        }
    }

    @Configuration
    @EnableAsync(proxyTargetClass = true)
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

        @Bean(name = "orderPaymentPostCommitExecutor")
        TaskExecutor orderPaymentPostCommitExecutor() {
            return new SyncTaskExecutor();
        }

        @Bean
        OrderPaidPostCommitEffects effects() {
            return mock(OrderPaidPostCommitEffects.class);
        }

        @Bean
        OrderPaidPostCommitListener listener(OrderPaidPostCommitEffects effects) {
            return new OrderPaidPostCommitListener(effects);
        }

        @Bean
        RollbackOnlyParticipant rollbackOnlyParticipant() {
            return new RollbackOnlyParticipant();
        }

        @Bean
        PaymentEventProbe paymentEventProbe(
                ApplicationEventPublisher publisher,
                RollbackOnlyParticipant participant
        ) {
            return new PaymentEventProbe(publisher, participant);
        }
    }

    static class PaymentEventProbe {
        private final ApplicationEventPublisher publisher;
        private final RollbackOnlyParticipant participant;

        PaymentEventProbe(ApplicationEventPublisher publisher, RollbackOnlyParticipant participant) {
            this.publisher = publisher;
            this.participant = participant;
        }

        @Transactional
        public void publishAndCommit(Long orderId) {
            publisher.publishEvent(new OrderPaidPostCommitEvent(orderId));
        }

        @Transactional
        public void publishAfterCaughtJoinedFailure(Long orderId) {
            try {
                participant.failInJoinedTransaction();
            } catch (IllegalStateException ignored) {
                // Reproduces the old catch-and-continue payment boundary.
            }
            publisher.publishEvent(new OrderPaidPostCommitEvent(orderId));
        }
    }

    static class RollbackOnlyParticipant {
        @Transactional
        public void failInJoinedTransaction() {
            throw new IllegalStateException("joined next-order persistence failure");
        }
    }
}
