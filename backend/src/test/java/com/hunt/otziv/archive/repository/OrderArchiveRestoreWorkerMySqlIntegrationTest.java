package com.hunt.otziv.archive.repository;

import com.hunt.otziv.archive.dto.ArchiveRecoveryWorkerOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the specialist SQL of the archive restore against real MySQL, outside any other restore step. */
@Testcontainers
class OrderArchiveRestoreWorkerMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("archive_restore_workers").withUsername("root").withPassword("root");
    static JdbcTemplate jdbc;
    static OrderArchiveRestoreRepository repository;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY, fio VARCHAR(100), username VARCHAR(100), active BOOLEAN)");
        jdbc.execute("CREATE TABLE workers(worker_id BIGINT PRIMARY KEY, user_id BIGINT UNIQUE)");
        jdbc.execute("CREATE TABLE roles(id BIGINT PRIMARY KEY, name VARCHAR(40))");
        jdbc.execute("CREATE TABLE users_roles(user_id BIGINT, role_id BIGINT)");
        jdbc.execute("CREATE TABLE managers_users(user_id BIGINT, manager_id BIGINT, PRIMARY KEY(user_id, manager_id))");
        jdbc.execute("CREATE TABLE workers_companies(company_id BIGINT, worker_id BIGINT, PRIMARY KEY(company_id, worker_id))");
        jdbc.execute("CREATE TABLE companies(company_id BIGINT PRIMARY KEY, company_manager BIGINT)");
        jdbc.execute("CREATE TABLE archive_orders(order_id BIGINT PRIMARY KEY, order_company BIGINT, order_worker BIGINT)");
        jdbc.execute("CREATE TABLE orders(order_id BIGINT PRIMARY KEY, order_company BIGINT, order_worker BIGINT, row_version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE order_details(order_detail_id BINARY(16) PRIMARY KEY, order_detail_order BIGINT)");
        jdbc.execute("CREATE TABLE reviews(review_id BIGINT PRIMARY KEY, review_order_details BINARY(16), review_worker BIGINT, review_publish BIT(1), row_version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE bad_review_tasks(bad_review_task_id BIGINT PRIMARY KEY, bad_review_task_order BIGINT, bad_review_task_worker BIGINT, bad_review_task_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE review_recovery_batches(review_recovery_batch_id BIGINT PRIMARY KEY, review_recovery_batch_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE review_recovery_tasks(review_recovery_task_id BIGINT PRIMARY KEY, review_recovery_task_batch BIGINT, review_recovery_task_order BIGINT, review_recovery_task_archive_order_id BIGINT, review_recovery_task_worker BIGINT, review_recovery_task_status VARCHAR(32), review_recovery_task_updated_at DATETIME(6) NOT NULL)");
        jdbc.execute("CREATE TABLE contractor_payment_allocations(id BIGINT PRIMARY KEY, order_id BIGINT, common_invoice_id BIGINT, mode VARCHAR(20), status VARCHAR(40))");
        jdbc.execute("CREATE TABLE common_invoice_orders(invoice_id BIGINT, order_id BIGINT)");

        jdbc.update("INSERT INTO users VALUES (1,'Former','former',false),(2,'Current','current',true),(3,'Foreign','foreign',true),(4,'Role removed','removed',true),(5,'Second','second',true)");
        jdbc.update("INSERT INTO workers VALUES (60,1),(72,2),(73,3),(74,4),(75,5)");
        jdbc.update("INSERT INTO roles VALUES (1,'ROLE_WORKER'),(2,'ROLE_MANAGER')");
        jdbc.update("INSERT INTO users_roles VALUES (1,1),(2,1),(3,1),(4,2),(5,1)");
        jdbc.update("INSERT INTO managers_users VALUES (1,9),(2,9),(3,10),(4,9),(5,9)");
        jdbc.update("INSERT INTO companies VALUES (20,9),(30,9)");
        jdbc.update("INSERT INTO workers_companies VALUES (20,60),(20,72),(30,60)");
        jdbc.update("INSERT INTO archive_orders VALUES (100,20,60),(101,20,72),(102,20,NULL),(200,30,60)");
        repository = new OrderArchiveRestoreRepository(new NamedParameterJdbcTemplate(ds));
    }

    @Test
    void archivedSpecialistIsActiveOnlyWhileTheUserWorksAsSpecialist() {
        assertThat(repository.findArchiveOrderWorker(100L))
                .isEqualTo(new OrderArchiveRestoreRepository.ArchiveOrderWorker(60L, false));
        assertThat(repository.findArchiveOrderWorker(101L))
                .isEqualTo(new OrderArchiveRestoreRepository.ArchiveOrderWorker(72L, true));
        assertThat(repository.findArchiveOrderWorker(102L))
                .isEqualTo(new OrderArchiveRestoreRepository.ArchiveOrderWorker(null, false));
        assertThat(repository.findArchiveOrderWorker(999L))
                .isEqualTo(new OrderArchiveRestoreRepository.ArchiveOrderWorker(null, false));
    }

    @Test
    void choicesAreActiveSpecialistsOfTheCompanyManagerWithCompanySpecialistsFirst() {
        assertThat(repository.findRestoreWorkerOptions(100L)).containsExactly(
                new ArchiveRecoveryWorkerOption(72L, "Current", true),
                new ArchiveRecoveryWorkerOption(75L, "Second", false));
        assertThat(repository.findRestoreWorkerOptions(999L)).isEmpty();
    }

    @Test
    void chosenSpecialistTakesOrderUnpublishedReviewsAndWaitingTasksOnly() {
        jdbc.update("INSERT INTO orders VALUES (200,30,60,3)");
        jdbc.update("INSERT INTO order_details VALUES (UNHEX('00000000000000000000000000000200'),200)");
        jdbc.update("INSERT INTO reviews VALUES (1,UNHEX('00000000000000000000000000000200'),60,b'0',0),"
                + "(2,UNHEX('00000000000000000000000000000200'),60,b'1',0)");
        jdbc.update("INSERT INTO bad_review_tasks VALUES (1,200,60,'NEW'),(2,200,60,'DONE')");
        jdbc.update("INSERT INTO review_recovery_batches VALUES (1,'OPEN'),(2,'CLIENT_NOTIFIED')");
        jdbc.update("INSERT INTO review_recovery_tasks VALUES "
                + "(1,1,NULL,200,60,'PLANNED','2026-01-01'),(2,1,NULL,200,60,'DONE','2026-01-01'),"
                + "(3,2,NULL,200,60,'PLANNED','2026-01-01'),(4,1,NULL,999,60,'PLANNED','2026-01-01'),"
                + "(5,1,200,NULL,60,'PLANNED','2026-01-01')");

        repository.reassignRestoredOrderWorker(200L, 75L);
        // A repeated call must not duplicate the company link.
        repository.reassignRestoredOrderWorker(200L, 75L);

        assertThat(jdbc.queryForObject("SELECT order_worker FROM orders WHERE order_id = 200", Long.class)).isEqualTo(75L);
        assertThat(jdbc.queryForList("SELECT review_worker FROM reviews ORDER BY review_id", Long.class))
                .containsExactly(75L, 60L);
        assertThat(jdbc.queryForList("SELECT bad_review_task_worker FROM bad_review_tasks ORDER BY bad_review_task_id", Long.class))
                .containsExactly(75L, 60L);
        assertThat(jdbc.queryForList("SELECT review_recovery_task_worker FROM review_recovery_tasks ORDER BY review_recovery_task_id", Long.class))
                .containsExactly(75L, 60L, 60L, 60L, 75L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workers_companies WHERE company_id = 30 AND worker_id = 75", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workers_companies WHERE company_id = 20 AND worker_id = 75", Integer.class))
                .isZero();
    }

    @Test
    void issuedContractorRequisitesFixTheRecipientDirectlyOrThroughACommonInvoice() {
        jdbc.update("INSERT INTO contractor_payment_allocations VALUES "
                + "(1,300,NULL,'LIVE','RESERVED'),(2,301,NULL,'LIVE','CONFIRMED'),(3,301,NULL,'SHADOW','RESERVED'),"
                + "(4,NULL,500,'LIVE','CLIENT_REPORTED')");
        jdbc.update("INSERT INTO common_invoice_orders VALUES (500,302)");

        assertThat(repository.hasFrozenLivePaymentRoute(300L)).isTrue();
        assertThat(repository.hasFrozenLivePaymentRoute(301L)).isFalse();
        assertThat(repository.hasFrozenLivePaymentRoute(302L)).isTrue();
        assertThat(repository.hasFrozenLivePaymentRoute(303L)).isFalse();
    }
}
