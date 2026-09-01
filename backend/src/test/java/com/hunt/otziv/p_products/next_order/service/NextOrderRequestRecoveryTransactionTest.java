package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.util.AopTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NextOrderRequestRecoveryTransactionTest {

    @Test
    void readOnlyRecoveryCommitDispatchesExistingAfterCommitListener() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TransactionTestConfiguration.class)) {
            NextOrderRequestRepository repository = context.getBean(NextOrderRequestRepository.class);
            NextOrderAutomationService automationService = AopTestUtils.getUltimateTargetObject(
                    context.getBean(NextOrderAutomationService.class)
            );
            NextOrderRequestRecoveryService recoveryService = context.getBean(NextOrderRequestRecoveryService.class);
            LocalDateTime dueBefore = LocalDateTime.of(2026, 9, 1, 10, 0);

            when(repository.findStaleRequestIds(
                    eq(Set.of(NextOrderRequestStatus.PENDING, NextOrderRequestStatus.FAILED)),
                    eq(dueBefore),
                    any(Pageable.class)
            )).thenReturn(List.of(77L));

            assertEquals(1, recoveryService.republishStaleRequests(dueBefore, 50));

            verify(automationService).createNextOrder(77L);
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

        @Bean(name = "nextOrderAutomationExecutor")
        TaskExecutor nextOrderAutomationExecutor() {
            return new SyncTaskExecutor();
        }

        @Bean
        NextOrderRequestRepository requestRepository() {
            return mock(NextOrderRequestRepository.class);
        }

        @Bean
        NextOrderAutomationService automationService() {
            return mock(NextOrderAutomationService.class);
        }

        @Bean
        NextOrderRequestService requestService() {
            return mock(NextOrderRequestService.class);
        }

        @Bean
        NextOrderAutomationListener automationListener(
                NextOrderAutomationService automationService,
                NextOrderRequestService requestService
        ) {
            return new NextOrderAutomationListener(automationService, requestService);
        }

        @Bean
        NextOrderRequestRecoveryService recoveryService(
                NextOrderRequestRepository repository,
                ApplicationEventPublisher publisher
        ) {
            return new NextOrderRequestRecoveryService(repository, publisher);
        }
    }
}
