package com.hunt.otziv.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common.events.ContractorAccrualAdjustedEvent;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ContractorAccrualAnalyticsRefreshServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private AnalyticsAggregateRebuildService rebuildService;
    @Mock private AnalyticsAggregateVerificationService verificationService;
    @Mock private PlatformTransactionManager transactionManager;

    private ContractorAccrualAnalyticsRefreshService service;

    @BeforeEach
    void setUp() {
        service = new ContractorAccrualAnalyticsRefreshService(
                jdbc, rebuildService, verificationService, transactionManager);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rollbackKeepsRefreshInCallerTransactionAndDoesNotScheduleRebuild() {
        assertThat(service.supportsAsyncExecution()).isFalse();
        service.onApplicationEvent(new ContractorAccrualAdjustedEvent(
                this, LocalDate.of(2026, 10, 16)));

        verify(jdbc).update(contains("INSERT INTO app_settings"),
                eq("contractor-accrual-analytics:2026-10"));
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void commitQueuesRefreshOnlyAfterMarkerWasWritten() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service.onApplicationEvent(new ContractorAccrualAdjustedEvent(
                this, LocalDate.of(2026, 11, 1)));

        verify(jdbc).update(contains("INSERT INTO app_settings"),
                eq("contractor-accrual-analytics:2026-11"));
        verify(jdbc, never()).query(contains("SELECT setting_value"),
                any(RowMapper.class), eq("contractor-accrual-analytics:2026-11"));

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(jdbc, timeout(5_000)).query(contains("SELECT setting_value"),
                any(RowMapper.class), eq("contractor-accrual-analytics:2026-11"));
    }
}
