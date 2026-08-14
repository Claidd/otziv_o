package com.hunt.otziv.common_billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class CommonInvoiceBoardQueryRepositoryMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";
    private static final LocalDateTime BLOCKER_CUTOFF = LocalDateTime.of(2026, 3, 1, 0, 0);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("common_invoice_board_contract")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private CommonInvoiceBoardQueryRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        repository = new CommonInvoiceBoardQueryRepository(new NamedParameterJdbcTemplate(dataSource));
        recreateSchema();
        seedBoard();
    }

    @Test
    void filtersCountsAndPaginatesBeforeEntityGraphsAreLoaded() {
        CommonInvoiceBoardQueryRepository.PageSelection firstPage = repository.findPage(
                "Все", "", null, Set.of(7L), false, 0, 2, BLOCKER_CUTOFF
        );
        CommonInvoiceBoardQueryRepository.PageSelection secondPage = repository.findPage(
                "Все", "", null, Set.of(7L), false, 1, 2, BLOCKER_CUTOFF
        );
        CommonInvoiceBoardQueryRepository.PageSelection reversePage = repository.findPage(
                "Все", "", null, Set.of(7L), true, 0, 2, BLOCKER_CUTOFF
        );

        assertThat(firstPage.totalCards()).isEqualTo(6);
        assertThat(firstPage.linkedOrderCount()).isEqualTo(6);
        assertThat(firstPage.invoiceIds()).containsExactly(1L, 2L);
        assertThat(secondPage.invoiceIds()).containsExactly(4L, 5L);
        assertThat(reversePage.invoiceIds()).containsExactly(7L, 6L);
    }

    @Test
    void cardAndLinkedOrderFiltersRetainTheirDifferentExistingSemantics() {
        CommonInvoiceBoardQueryRepository.PageSelection byLinkedCompany = repository.findPage(
                "Выставлен счет", "alpha", 10L, Set.of(7L), false, 0, 20, BLOCKER_CUTOFF
        );
        CommonInvoiceBoardQueryRepository.PageSelection byAccountKeyword = repository.findPage(
                "Выставлен счет", "delegated", 10L, Set.of(7L), false, 0, 20, BLOCKER_CUTOFF
        );

        assertThat(byLinkedCompany.totalCards()).isEqualTo(1);
        assertThat(byLinkedCompany.invoiceIds()).containsExactly(2L);
        assertThat(byLinkedCompany.linkedOrderCount()).isEqualTo(1);

        assertThat(byAccountKeyword.totalCards()).isEqualTo(1);
        assertThat(byAccountKeyword.invoiceIds()).containsExactly(2L);
        assertThat(byAccountKeyword.linkedOrderCount()).isZero();
    }

    @Test
    void metricsUseEffectiveInvoiceStatusAndPerItemManagerVisibility() {
        CommonInvoiceBoardQueryRepository.BoardMetrics metrics = repository.metrics(Set.of(7L), BLOCKER_CUTOFF);

        assertThat(metrics.cardCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Опубликовано", 1,
                "Выставлен счет", 1,
                "Требует внимания", 1,
                "Ожидает общего счета", 1,
                "Напоминание", 1,
                "Не оплачено", 1
        ));
        assertThat(metrics.linkedOrderCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Опубликовано", 2,
                "Выставлен счет", 1,
                "В проверку", 1,
                "Новый", 1
        ));
    }

    @Test
    void linkedOrderMetricsIgnoreInactivePredecessorMembership() {
        insertInvoice(8, 1, "UNPAID", 8);
        jdbc.update("""
                INSERT INTO common_invoice_orders (
                    invoice_order_id, invoice_id, active_membership, order_id,
                    ready, publication_blocker_since
                ) VALUES (9, 8, FALSE, 101, TRUE, NULL)
                """);

        CommonInvoiceBoardQueryRepository.BoardMetrics metrics =
                repository.metrics(Set.of(7L), BLOCKER_CUTOFF);

        assertThat(metrics.linkedOrderCounts().get("Опубликовано")).isEqualTo(2);
    }

    @Test
    void nullVisibilityIsUnrestrictedWhileEmptyVisibilityReturnsNothing() {
        CommonInvoiceBoardQueryRepository.PageSelection unrestricted = repository.findPage(
                "Все", "", null, null, false, 0, 20, BLOCKER_CUTOFF
        );
        CommonInvoiceBoardQueryRepository.PageSelection none = repository.findPage(
                "Все", "", null, Set.of(), false, 0, 20, BLOCKER_CUTOFF
        );

        assertThat(unrestricted.totalCards()).isEqualTo(7);
        assertThat(unrestricted.linkedOrderCount()).isEqualTo(8);
        assertThat(none.totalCards()).isZero();
        assertThat(none.linkedOrderCount()).isZero();
    }

    private void recreateSchema() {
        jdbc.execute("DROP TABLE IF EXISTS review_recovery_tasks");
        jdbc.execute("DROP TABLE IF EXISTS review_recovery_batches");
        jdbc.execute("DROP TABLE IF EXISTS common_invoice_orders");
        jdbc.execute("DROP TABLE IF EXISTS common_invoices");
        jdbc.execute("DROP TABLE IF EXISTS common_billing_accounts");
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("DROP TABLE IF EXISTS filial");
        jdbc.execute("DROP TABLE IF EXISTS companies");
        jdbc.execute("DROP TABLE IF EXISTS order_statuses");
        jdbc.execute("""
                CREATE TABLE order_statuses (
                    order_status_id BIGINT PRIMARY KEY,
                    order_status_title VARCHAR(100) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE companies (
                    company_id BIGINT PRIMARY KEY,
                    company_title VARCHAR(255) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE filial (
                    filial_id BIGINT PRIMARY KEY,
                    filial_title VARCHAR(255) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE orders (
                    order_id BIGINT PRIMARY KEY,
                    order_manager BIGINT NULL,
                    order_status BIGINT NULL,
                    order_company BIGINT NULL,
                    order_filial BIGINT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE common_billing_accounts (
                    account_id BIGINT PRIMARY KEY,
                    account_name VARCHAR(160) NOT NULL,
                    manager_id BIGINT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE common_invoices (
                    invoice_id BIGINT PRIMARY KEY,
                    account_id BIGINT NOT NULL,
                    title VARCHAR(180) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    INDEX idx_board_status_updated (status, updated_at, invoice_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE common_invoice_orders (
                    invoice_order_id BIGINT PRIMARY KEY,
                    invoice_id BIGINT NOT NULL,
                    active_membership BOOLEAN NOT NULL DEFAULT TRUE,
                    order_id BIGINT NOT NULL,
                    active_order_id BIGINT
                      GENERATED ALWAYS AS (
                        CASE WHEN active_membership THEN order_id ELSE NULL END
                      ) STORED,
                    ready BOOLEAN NOT NULL,
                    publication_blocker_since DATETIME(6) NULL,
                    UNIQUE KEY uk_board_active_order (active_order_id),
                    INDEX idx_board_invoice (invoice_id, ready)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE review_recovery_batches (
                    review_recovery_batch_id BIGINT PRIMARY KEY,
                    review_recovery_batch_status VARCHAR(32) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE review_recovery_tasks (
                    review_recovery_task_id BIGINT PRIMARY KEY,
                    review_recovery_task_batch BIGINT NOT NULL,
                    review_recovery_task_order BIGINT NULL,
                    review_recovery_task_status VARCHAR(32) NOT NULL,
                    INDEX idx_board_recovery (review_recovery_task_order, review_recovery_task_status)
                ) ENGINE=InnoDB
                """);
    }

    private void seedBoard() {
        jdbc.update("""
                INSERT INTO order_statuses (order_status_id, order_status_title)
                VALUES (1, 'Опубликовано'), (2, 'Выставлен счет'), (3, 'В проверку'), (4, 'Новый')
                """);
        jdbc.update("INSERT INTO companies (company_id, company_title) VALUES (10, 'Alpha LLC'), (20, 'Beta LLC')");
        jdbc.update("INSERT INTO filial (filial_id, filial_title) VALUES (100, 'Alpha Central'), (200, 'Beta North')");
        jdbc.update("""
                INSERT INTO common_billing_accounts (account_id, account_name, manager_id)
                VALUES (1, 'Primary payer', 7), (2, 'Delegated payer', NULL), (3, 'Hidden payer', NULL)
                """);
        insertOrder(101, 7, 1, 10, 100);
        insertOrder(102, 7, 2, 10, 100);
        insertOrder(201, 7, 3, 10, 100);
        insertOrder(301, 8, 1, 20, 200);
        insertOrder(401, 7, 4, 20, 200);
        insertOrder(501, 7, 1, 20, 200);
        insertOrder(601, 7, null, 20, 200);
        insertOrder(701, 8, 3, 10, 100);

        insertInvoice(1, 1, "COLLECTING", 1);
        insertInvoice(2, 2, "INVOICED", 2);
        insertInvoice(3, 3, "READY", 3);
        insertInvoice(4, 1, "COLLECTING", 4);
        insertInvoice(5, 1, "READY", 5);
        insertInvoice(6, 1, "REMINDER", 6);
        insertInvoice(7, 1, "UNPAID", 7);

        insertItem(1, 1, 101, true, null);
        insertItem(2, 1, 102, true, null);
        insertItem(3, 2, 201, true, null);
        insertItem(4, 3, 301, true, null);
        insertItem(5, 4, 401, false, LocalDateTime.of(2026, 2, 1, 0, 0));
        insertItem(6, 5, 501, true, null);
        insertItem(7, 6, 601, true, null);
        insertItem(8, 7, 701, true, null);

        jdbc.update("""
                INSERT INTO review_recovery_batches (
                    review_recovery_batch_id, review_recovery_batch_status
                ) VALUES (1, 'OPEN')
                """);
        jdbc.update("""
                INSERT INTO review_recovery_tasks (
                    review_recovery_task_id, review_recovery_task_batch,
                    review_recovery_task_order, review_recovery_task_status
                ) VALUES (1, 1, 501, 'PLANNED')
                """);
    }

    private void insertOrder(long id, long managerId, Integer statusId, long companyId, long filialId) {
        jdbc.update(
                "INSERT INTO orders (order_id, order_manager, order_status, order_company, order_filial) VALUES (?, ?, ?, ?, ?)",
                id, managerId, statusId, companyId, filialId
        );
    }

    private void insertInvoice(long id, long accountId, String status, int day) {
        jdbc.update(
                "INSERT INTO common_invoices (invoice_id, account_id, title, status, updated_at) VALUES (?, ?, ?, ?, ?)",
                id, accountId, "Invoice " + id, status, LocalDateTime.of(2026, 1, day, 0, 0)
        );
    }

    private void insertItem(
            long id,
            long invoiceId,
            long orderId,
            boolean ready,
            LocalDateTime blockerSince
    ) {
        jdbc.update("""
                        INSERT INTO common_invoice_orders (
                            invoice_order_id, invoice_id, order_id, ready, publication_blocker_since
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                id, invoiceId, orderId, ready, blockerSince
        );
    }
}
