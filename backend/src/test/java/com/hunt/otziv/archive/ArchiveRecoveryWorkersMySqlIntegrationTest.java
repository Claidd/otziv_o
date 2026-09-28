package com.hunt.otziv.archive;

import com.hunt.otziv.archive.dto.ArchiveAccessScope;
import com.hunt.otziv.archive.dto.ArchiveRecoveryWorkerOption;
import com.hunt.otziv.archive.repository.ManagerArchiveRepository;
import com.hunt.otziv.security.credentials.CredentialCipher;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers
class ArchiveRecoveryWorkersMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("archive_workers").withUsername("root").withPassword("root");
    static ManagerArchiveRepository repository;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, fio VARCHAR(100), username VARCHAR(100), active BOOLEAN)");
        jdbc.execute("CREATE TABLE workers(worker_id BIGINT PRIMARY KEY, user_id BIGINT)");
        jdbc.execute("CREATE TABLE roles(id BIGINT PRIMARY KEY, name VARCHAR(40))");
        jdbc.execute("CREATE TABLE users_roles(user_id BIGINT, role_id BIGINT)");
        jdbc.execute("CREATE TABLE managers_users(user_id BIGINT, manager_id BIGINT)");
        jdbc.execute("CREATE TABLE workers_companies(worker_id BIGINT, company_id BIGINT)");
        jdbc.update("INSERT INTO users VALUES (1,'Former','former',false),(2,'Current','current',true),(3,'Foreign','foreign',true),(4,'Role removed','removed',true)");
        jdbc.update("INSERT INTO workers VALUES (60,1),(72,2),(73,3),(74,4)");
        jdbc.update("INSERT INTO roles VALUES (1,'ROLE_WORKER'),(2,'ROLE_MANAGER')");
        jdbc.update("INSERT INTO users_roles VALUES (1,1),(2,1),(3,1),(4,2)");
        jdbc.update("INSERT INTO managers_users VALUES (1,9),(2,9),(3,10),(4,9)");
        jdbc.update("INSERT INTO workers_companies VALUES (60,20),(72,20),(74,20)");
        jdbc.execute("CREATE TABLE review_recovery_batches(review_recovery_batch_id BIGINT PRIMARY KEY, review_recovery_batch_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE review_recovery_tasks(review_recovery_task_id BIGINT PRIMARY KEY, review_recovery_task_batch BIGINT, review_recovery_task_archive_order_id BIGINT, review_recovery_task_archive_review_id BIGINT, review_recovery_task_worker BIGINT, review_recovery_task_scheduled_date DATE, review_recovery_task_status VARCHAR(32))");
        jdbc.update("INSERT INTO review_recovery_batches VALUES (1,'OPEN'),(2,'ARCHIVED')");
        jdbc.update("INSERT INTO review_recovery_tasks VALUES (1238,1,10,11,60,'2026-09-28','PLANNED'),(1239,1,10,12,72,'2026-09-28','DONE'),(1240,1,10,13,72,'2026-09-28','CANCELLED'),(1241,2,10,14,72,'2026-09-28','DONE'),(1242,1,99,15,72,'2026-09-28','PLANNED')");
        repository = new ManagerArchiveRepository(new NamedParameterJdbcTemplate(ds), mock(CredentialCipher.class));
    }

    @Test
    void scopedOptionsExcludeFormerWorkersRoleChangesAndForeignManagers() {
        assertThat(repository.findRecoveryWorkers(ArchiveAccessScope.managers(Set.of(9L)), 20L))
                .containsExactly(new ArchiveRecoveryWorkerOption(72L, "Current", true));
        assertThat(repository.findRecoveryWorkers(ArchiveAccessScope.managers(Set.of()), 20L)).isEmpty();
        assertThat(repository.findRecoveryWorkers(ArchiveAccessScope.all(), 20L))
                .extracting(ArchiveRecoveryWorkerOption::id).containsExactly(72L, 73L);
    }

    @Test
    void taskListIncludesFormerAssigneeAndExcludesCancelledAndOtherOrders() {
        var tasks = repository.findRecoveryTasks(10L);
        assertThat(tasks).extracting(task -> task.id()).containsExactly(1238L, 1239L);
        assertThat(tasks.getFirst().workerName()).isEqualTo("Former");
        assertThat(tasks.getFirst().workerId()).isEqualTo(60L);
    }
}
