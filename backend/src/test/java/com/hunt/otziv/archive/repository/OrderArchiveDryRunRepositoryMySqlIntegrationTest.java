package com.hunt.otziv.archive.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class OrderArchiveDryRunRepositoryMySqlIntegrationTest {

    private static final LocalDate CUTOFF_DATE = LocalDate.of(2026, 1, 1);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("order_archive_drift_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private OrderArchiveDryRunRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        initializeSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        repository = new OrderArchiveDryRunRepository(new NamedParameterJdbcTemplate(dataSource));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        jdbc.update("""
                INSERT INTO order_statuses (order_status_id, order_status_title)
                VALUES (1, 'Оплачено'), (2, 'В работе')
                """);
    }

    @Test
    void statusReactivationAfterPreparationIsDetectedByMySqlDriftQuery() {
        insertPaidOrder(101L);

        Boolean eligibilityDrift = transaction.execute(status -> {
            repository.prepareCandidateOrders(CUTOFF_DATE, 10);

            assertThat(repository.lockPreparedCandidateOrders()).isEqualTo(1);
            assertThat(repository.lockPreparedCandidateCommonInvoices()).isZero();
            assertThat(repository.hasPreparedCandidateEligibilityDrift(CUTOFF_DATE)).isFalse();

            jdbc.update("UPDATE orders SET order_status = 2 WHERE order_id = 101");
            return repository.hasPreparedCandidateEligibilityDrift(CUTOFF_DATE);
        });

        assertThat(eligibilityDrift).isTrue();
    }

    @Test
    void newCommonInvoiceMemberAfterPreparationIsDetectedByMySqlDriftQuery() {
        insertPaidOrder(201L);
        jdbc.update("""
                INSERT INTO common_invoices (invoice_id, status, closed_at)
                VALUES (301, 'PAID', '2025-01-01 00:00:00')
                """);
        jdbc.update("""
                INSERT INTO common_invoice_orders (invoice_order_id, invoice_id, order_id)
                VALUES (401, 301, 201)
                """);

        Boolean membershipDrift = transaction.execute(status -> {
            repository.prepareCandidateOrders(CUTOFF_DATE, 10);

            assertThat(repository.lockPreparedCandidateOrders()).isEqualTo(1);
            assertThat(repository.countPreparedCandidateCommonInvoices()).isEqualTo(1);
            assertThat(repository.lockPreparedCandidateCommonInvoices()).isEqualTo(1);
            assertThat(repository.hasPreparedCandidateEligibilityDrift(CUTOFF_DATE)).isFalse();

            insertPaidOrder(202L);
            jdbc.update("""
                    INSERT INTO common_invoice_orders (invoice_order_id, invoice_id, order_id)
                    VALUES (402, 301, 202)
                    """);
            return repository.hasPreparedCandidateEligibilityDrift(CUTOFF_DATE);
        });

        assertThat(membershipDrift).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INIT_PREPARED", "INIT_CONFLICT", "CURRENT", "CONFIRMED", "PREPAID", "APPLYING",
            "CANCEL_PENDING", "CANCELING", "CANCEL_FAILED", "CANCEL_FAILED_FINAL",
            "NEW", "AUTHORIZED", "WEBHOOK", "FUTURE_PROVIDER_STATE"
    })
    void unresolvedPaymentRegistryLifecycleBlocksCommonInvoiceArchive(String paymentRefStatus) {
        insertPaidCommonInvoiceWithPaymentRef(paymentRefStatus);

        assertThat(repository.countEligibleOrders(CUTOFF_DATE)).isZero();
        transaction.executeWithoutResult(status -> {
            repository.prepareCandidateOrders(CUTOFF_DATE, 10);
            assertThat(repository.countPreparedCandidateCommonInvoices()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "APPLIED", "ARCHIVED", "CANCELED", "REJECTED",
            "REFUNDED", "PARTIAL_REFUNDED", "REVERSED", "PARTIAL_REVERSED"
    })
    void terminalPaymentRegistryLifecycleAllowsCommonInvoiceArchive(String paymentRefStatus) {
        insertPaidCommonInvoiceWithPaymentRef(paymentRefStatus);

        assertThat(repository.countEligibleOrders(CUTOFF_DATE)).isEqualTo(1);
        transaction.executeWithoutResult(status -> {
            repository.prepareCandidateOrders(CUTOFF_DATE, 10);
            assertThat(repository.countPreparedCandidateCommonInvoices()).isEqualTo(1);
        });
    }

    @Test
    void paymentReferenceReactivationAfterPreparationIsDetectedByMySqlDriftQuery() {
        insertPaidCommonInvoiceWithPaymentRef("APPLIED");

        Boolean eligibilityDrift = transaction.execute(status -> {
            repository.prepareCandidateOrders(CUTOFF_DATE, 10);

            assertThat(repository.lockPreparedCandidateOrders()).isEqualTo(1);
            assertThat(repository.lockPreparedCandidateCommonInvoices()).isEqualTo(1);
            assertThat(repository.hasPreparedCandidateEligibilityDrift(CUTOFF_DATE)).isFalse();

            jdbc.update("""
                    UPDATE common_invoice_payment_refs
                    SET status = 'CURRENT'
                    WHERE invoice_id = 601
                    """);
            return repository.hasPreparedCandidateEligibilityDrift(CUTOFF_DATE);
        });

        assertThat(eligibilityDrift).isTrue();
    }

    private void insertPaidOrder(long orderId) {
        jdbc.update("""
                INSERT INTO orders (
                    order_id,
                    order_status,
                    order_pay_day,
                    order_changed,
                    order_created,
                    order_status_changed_at
                ) VALUES (?, 1, '2025-01-01', '2025-01-01', '2025-01-01', '2025-01-01 00:00:00')
                """, orderId);
    }

    private void insertPaidCommonInvoiceWithPaymentRef(String paymentRefStatus) {
        insertPaidOrder(501L);
        jdbc.update("""
                INSERT INTO common_invoices (invoice_id, status, closed_at)
                VALUES (601, 'PAID', '2025-01-01 00:00:00')
                """);
        jdbc.update("""
                INSERT INTO common_invoice_orders (invoice_order_id, invoice_id, order_id)
                VALUES (701, 601, 501)
                """);
        jdbc.update("""
                INSERT INTO common_invoice_payment_refs (invoice_id, status)
                VALUES (601, ?)
                """, paymentRefStatus);
    }

    private void initializeSchema(DataSource dataSource) {
        JdbcTemplate setup = new JdbcTemplate(dataSource);
        setup.execute("DROP TABLE IF EXISTS payment_success_notification_retry_claims");
        setup.execute("DROP TABLE IF EXISTS common_invoice_payment_refs");
        setup.execute("DROP TABLE IF EXISTS common_invoice_orders");
        setup.execute("DROP TABLE IF EXISTS common_invoices");
        setup.execute("DROP TABLE IF EXISTS payment_links");
        setup.execute("DROP TABLE IF EXISTS next_order_requests");
        setup.execute("DROP TABLE IF EXISTS bad_review_tasks");
        setup.execute("DROP TABLE IF EXISTS orders");
        setup.execute("DROP TABLE IF EXISTS order_statuses");

        setup.execute("""
                CREATE TABLE order_statuses (
                    order_status_id BIGINT NOT NULL,
                    order_status_title VARCHAR(64) NOT NULL,
                    PRIMARY KEY (order_status_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE orders (
                    order_id BIGINT NOT NULL,
                    order_status BIGINT NOT NULL,
                    order_pay_day DATE NULL,
                    order_changed DATE NULL,
                    order_created DATE NULL,
                    order_status_changed_at DATETIME(6) NULL,
                    PRIMARY KEY (order_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE bad_review_tasks (
                    bad_review_task_order BIGINT NOT NULL,
                    bad_review_task_status VARCHAR(32) NOT NULL
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE next_order_requests (
                    source_order_id BIGINT NOT NULL,
                    request_status VARCHAR(32) NOT NULL
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE payment_links (
                    id BIGINT NOT NULL,
                    order_id BIGINT NOT NULL,
                    status VARCHAR(32) NULL,
                    bank_init_nonce VARCHAR(64) NULL,
                    bank_cancel_nonce VARCHAR(64) NULL,
                    bank_cancel_origin_status VARCHAR(32) NULL,
                    receipt_status VARCHAR(32) NULL,
                    payment_success_notified_at DATETIME(6) NULL,
                    payment_success_notification_retry_eligible TINYINT(1) NOT NULL DEFAULT 0,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE payment_success_notification_retry_claims (
                    payment_link_id BIGINT NOT NULL,
                    processing_lease_until DATETIME(6) NOT NULL,
                    PRIMARY KEY (payment_link_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE common_invoices (
                    invoice_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    closed_at DATETIME(6) NULL,
                    PRIMARY KEY (invoice_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE common_invoice_orders (
                    invoice_order_id BIGINT NOT NULL,
                    invoice_id BIGINT NOT NULL,
                    order_id BIGINT NOT NULL,
                    PRIMARY KEY (invoice_order_id)
                ) ENGINE=InnoDB
                """);
        setup.execute("""
                CREATE TABLE common_invoice_payment_refs (
                    invoice_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL
                ) ENGINE=InnoDB
                """);
    }
}
