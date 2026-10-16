package com.hunt.otziv.analytics.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refreshes cached analytics after a monetary accrual adjustment commits.
 * A pending month is recorded in the adjustment's transaction, so a failed
 * refresh or process restart can be retried without changing the adjustment.
 * Profile balances and payment routing read live sources immediately; cached
 * analytics can briefly lag while the bounded background rebuild runs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorAccrualAnalyticsRefreshService {

    private static final String PENDING_PREFIX = "contractor-accrual-analytics:";
    private static final ZoneId ACCOUNTING_ZONE = ZoneId.of("Asia/Irkutsk");

    private final JdbcTemplate jdbc;
    private final AnalyticsAggregateRebuildService rebuildService;
    private final AnalyticsAggregateVerificationService verificationService;
    private final PlatformTransactionManager transactionManager;
    private final Set<LocalDate> rebuilding = ConcurrentHashMap.newKeySet();
    private final ThreadPoolTaskExecutor executor = newExecutor();

    /** Call inside the same transaction that inserts the adjustment. */
    public void refreshAfterCommit(LocalDate effectiveOn) {
        if (effectiveOn == null) {
            throw new IllegalArgumentException("effectiveOn is required");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Accrual analytics refresh requires an active write transaction");
        }
        LocalDate month = effectiveOn.withDayOfMonth(1);
        jdbc.update("""
                INSERT INTO app_settings (setting_key, setting_value, updated_at)
                VALUES (?, '1', CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    setting_value = CAST(CAST(setting_value AS UNSIGNED) + 1 AS CHAR),
                    updated_at = CURRENT_TIMESTAMP(6)
                """, key(month));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    executor.execute(() -> refreshPendingMonth(month));
                } catch (RuntimeException exception) {
                    // The durable marker was committed with the adjustment.
                    // The scheduled retry will pick it up even if the queue is full.
                    log.error("Accrual analytics refresh enqueue failed for month {}; pending retry retained",
                            month, exception);
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    @Scheduled(
            initialDelayString = "${otziv.analytics.accrual-refresh.initial-delay-ms:60000}",
            fixedDelayString = "${otziv.analytics.accrual-refresh.retry-delay-ms:600000}"
    )
    public void retryPending() {
        try {
            List<String> keys = jdbc.query("""
                    SELECT setting_key
                    FROM app_settings
                    WHERE setting_key LIKE 'contractor-accrual-analytics:%'
                    ORDER BY setting_key
                    """, (rs, rowNum) -> rs.getString(1));
            for (String key : keys) {
                try {
                    refreshPendingMonth(YearMonth.parse(key.substring(PENDING_PREFIX.length())).atDay(1));
                } catch (RuntimeException malformedKey) {
                    log.error("Invalid pending accrual analytics month: {}", key, malformedKey);
                }
            }
        } catch (RuntimeException exception) {
            log.error("Unable to scan pending accrual analytics refreshes", exception);
        }
    }

    private void refreshPendingMonth(LocalDate month) {
        if (!rebuilding.add(month)) {
            // Another local refresh owns the month; the durable marker remains
            // for the retry job if a later adjustment commits during its run.
            return;
        }
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(status -> {
                String pendingKey = key(month);
                List<String> generations = jdbc.query(
                        "SELECT setting_value FROM app_settings WHERE setting_key = ?",
                        (rs, rowNum) -> rs.getString(1), pendingKey);
                if (generations.isEmpty()) {
                    return;
                }
                String generation = generations.getFirst();
                boolean closed = month.isBefore(LocalDate.now(ACCOUNTING_ZONE).withDayOfMonth(1));
                rebuildService.rebuildMonth(month, closed);
                if (!verificationService.compareAdminMonth(month).matches()) {
                    throw new IllegalStateException("Accrual analytics verification failed for month " + month);
                }
                // A concurrent adjustment changes the generation. Its pending
                // marker then survives this rebuild and gets another pass.
                jdbc.update("DELETE FROM app_settings WHERE setting_key = ? AND setting_value = ?",
                        pendingKey, generation);
            });
        } catch (RuntimeException exception) {
            log.error("Accrual analytics refresh failed for month {}; pending retry retained", month, exception);
        } finally {
            rebuilding.remove(month);
        }
    }

    private static String key(LocalDate month) {
        return PENDING_PREFIX + YearMonth.from(month);
    }

    private static ThreadPoolTaskExecutor newExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setQueueCapacity(32);
        taskExecutor.setThreadNamePrefix("accrual-analytics-refresh-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(false);
        taskExecutor.initialize();
        return taskExecutor;
    }
}
