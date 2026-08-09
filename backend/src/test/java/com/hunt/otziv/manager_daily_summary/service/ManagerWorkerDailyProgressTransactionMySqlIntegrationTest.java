package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ManagerWorkerDailyProgressTransactionMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    )
            .withDatabaseName("workload_progress_transaction")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS workload_progress_transaction_probe");
        jdbc.execute("""
                CREATE TABLE workload_progress_transaction_probe (
                    id BIGINT NOT NULL,
                    reconciliation_count INT NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.update("""
                INSERT INTO workload_progress_transaction_probe (
                    id,
                    reconciliation_count
                ) VALUES (1, 0)
                """);
    }

    @Test
    void historicalProgressAllowsReconciliationUpdateInsideServiceTransaction() {
        LocalDate date = LocalDate.of(2026, 8, 8);
        ManagerRepository managerRepository = mock(ManagerRepository.class);
        StaffDailyProgressService progressService = mock(StaffDailyProgressService.class);
        Manager manager = Manager.builder()
                .id(1L)
                .user(User.builder().id(10L).workers(Set.of()).build())
                .build();

        when(progressService.progressEnabled()).thenReturn(true);
        when(managerRepository.findAllWithUserAndImage()).thenReturn(List.of(manager));
        when(managerRepository.findAllManagersWorkers(List.of(manager))).thenReturn(List.of(manager));
        when(progressService.workerEndOfDayProgressByWorkers(
                anyCollection(),
                any(LocalDate.class),
                isNull()
        )).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            jdbc.update("""
                    UPDATE workload_progress_transaction_probe
                    SET reconciliation_count = reconciliation_count + 1
                    WHERE id = 1
                    """);
            return Map.of();
        });

        ManagerWorkerDailyProgressService service = transactionalProxy(
                new ManagerWorkerDailyProgressService(managerRepository, progressService)
        );

        service.progressByManagerIds(List.of(1L), date);

        assertThat(jdbc.queryForObject(
                """
                        SELECT reconciliation_count
                        FROM workload_progress_transaction_probe
                        WHERE id = 1
                        """,
                Integer.class
        )).isEqualTo(2);
    }

    private ManagerWorkerDailyProgressService transactionalProxy(
            ManagerWorkerDailyProgressService target
    ) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(
                new AnnotationTransactionAttributeSource()
        );
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (ManagerWorkerDailyProgressService) factory.getProxy();
    }
}
