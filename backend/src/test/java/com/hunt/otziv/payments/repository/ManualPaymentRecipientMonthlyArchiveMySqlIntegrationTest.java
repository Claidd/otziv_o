package com.hunt.otziv.payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ManualPaymentRecipientMonthlyArchiveMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    ).withDatabaseName("manual_recipient_monthly")
            .withUsername("root")
            .withPassword("root");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate namedJdbc;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        dropTables();
        createTables();
    }

    @Test
    void paymentLinkLegacyFactsSurviveArchiveWithoutDuplicatingLiveOrTypedRows() throws Exception {
        jdbc.update("""
                INSERT INTO payment_links
                    (id, status, payment_method, amount_kopecks, reserved_amount_kopecks,
                     confirmed_amount_kopecks, manual_confirmed_at, paid_at)
                VALUES
                    (1, 'CONFIRMED', 'MANUAL_MOBILE_BANK', 100, NULL, 100,
                     '2026-08-02 10:00:00', NULL),
                    (3, 'CONFIRMED', 'MANUAL_EXTERNAL_LINK', 300, NULL, 300,
                     '2026-08-03 10:00:00', NULL),
                    (4, 'CONFIRMED', 'MANUAL_MOBILE_BANK', 400, NULL, 400,
                     '2026-08-04 10:00:00', NULL),
                    (6, 'REFUNDED', 'MANUAL_MOBILE_BANK', 600, NULL, 600,
                     '2026-08-06 10:00:00', NULL),
                    (7, 'PARTIALLY_RETURNED', 'MANUAL_EXTERNAL_LINK', 700, NULL, 700,
                     '2026-08-07 10:00:00', NULL),
                    (8, 'CANCELED', 'MANUAL_MOBILE_BANK', 800, NULL, 800,
                     NULL, '2026-08-08 10:00:00')
                """);
        jdbc.update("""
                INSERT INTO archive_payment_links
                    (id, status, payment_method, amount_kopecks, reserved_amount_kopecks,
                     confirmed_amount_kopecks, manual_confirmed_at, paid_at)
                VALUES
                    (2, 'CONFIRMED', 'MANUAL_MOBILE_BANK', 200, NULL, 200,
                     '2026-08-02 11:00:00', NULL),
                    (3, 'CONFIRMED', 'MANUAL_EXTERNAL_LINK', 300, NULL, 300,
                     '2026-08-03 10:00:00', NULL),
                    (5, 'CONFIRMED', 'MANUAL_MOBILE_BANK', 500, NULL, 500,
                     '2026-08-05 10:00:00', NULL),
                    (9, 'REVERSED', 'MANUAL_MOBILE_BANK', 900, NULL, 900,
                     '2026-08-09 10:00:00', NULL),
                    (10, 'REJECTED', 'MANUAL_EXTERNAL_LINK', 1000, NULL, 1000,
                     NULL, '2026-08-10 10:00:00')
                """);
        jdbc.update("""
                INSERT INTO contractor_actual_payment_attributions
                    (source_kind, source_id, evidence_id)
                VALUES ('PAYMENT_LINK', 4, NULL), ('PAYMENT_LINK', 99, 5)
                """);

        Map<Long, Long> rows = execute(repositorySql(
                PaymentLinkRepository.class,
                LocalDateTime.class,
                LocalDateTime.class
        ));

        assertThat(rows).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, 100L,
                2L, 200L,
                3L, 300L,
                6L, 600L,
                7L, 700L,
                9L, 900L
        ));
    }

    @Test
    void commonLegacyFactsUseItemEvidenceAndCanonicalLiveOrArchiveCopy() throws Exception {
        jdbc.update("""
                INSERT INTO common_invoices (invoice_id, payment_method)
                VALUES (10, 'MANUAL'), (11, 'MANUAL'), (21, 'MANUAL')
                """);
        jdbc.update("""
                INSERT INTO common_invoice_orders
                    (invoice_order_id, invoice_id, amount_kopecks, paid, paid_at, payment_method,
                     source_payment_link_id, manual_paid_by, manual_payment_comment,
                     manual_payment_receipt_url, actual_payment_evidence_reference)
                VALUES
                    (1000, 10, 1000, 1, '2026-08-01 10:00:00', 'MANUAL',
                     NULL, 'manager', '', '', NULL),
                    (1001, 10, 1500, 1, '2026-08-02 10:00:00', 'MANUAL',
                     NULL, 'manager', '', '', 'COMMON_INVOICE:10:batch'),
                    (1100, 11, 500, 1, '2026-08-03 10:00:00', 'MANUAL',
                     1, 'manager', '', '', NULL),
                    (2100, 21, 3000, 1, '2026-08-04 10:00:00', 'MANUAL',
                     NULL, 'manager', '', '', NULL)
                """);
        jdbc.update("""
                INSERT INTO archive_common_invoices (invoice_id, payment_method, restored_at)
                VALUES
                    (20, 'MANUAL', NULL),
                    (21, 'MANUAL', '2026-08-10 10:00:00')
                """);
        jdbc.update("""
                INSERT INTO archive_common_invoice_orders
                    (invoice_order_id, invoice_id, amount_kopecks, paid, paid_at, payment_method,
                     source_payment_link_id, manual_paid_by, manual_payment_comment,
                     manual_payment_receipt_url, actual_payment_evidence_reference)
                VALUES
                    (2000, 20, 2000, 1, '2026-08-03 12:00:00', 'MANUAL',
                     NULL, 'manager', '', '', NULL),
                    (2100, 21, 3000, 1, '2026-08-04 10:00:00', 'MANUAL',
                     NULL, 'manager', '', '', NULL)
                """);

        Map<Long, Long> rows = execute(repositorySql(
                CommonInvoiceRepository.class,
                LocalDateTime.class,
                LocalDateTime.class
        ));

        assertThat(rows).containsExactlyInAnyOrderEntriesOf(Map.of(
                10L, 1000L,
                20L, 2000L,
                21L, 3000L
        ));
    }

    private Map<Long, Long> execute(String sql) {
        Map<String, Object> params = Map.of(
                "from", LocalDateTime.of(2026, 8, 1, 0, 0),
                "to", LocalDateTime.of(2026, 9, 1, 0, 0)
        );
        Map<Long, Long> result = new LinkedHashMap<>();
        namedJdbc.query(sql, params, rs -> {
            result.put(rs.getLong("sourceId"), rs.getLong("amountKopecks"));
        });
        return result;
    }

    private String repositorySql(Class<?> repositoryType, Class<?>... parameterTypes) throws Exception {
        Method method = repositoryType.getMethod(
                "findLegacyManualConfirmedForMonthlyRecipientSummary",
                parameterTypes
        );
        return method.getAnnotation(Query.class).value();
    }

    private void dropTables() {
        jdbc.execute("DROP TABLE IF EXISTS archive_common_invoice_orders");
        jdbc.execute("DROP TABLE IF EXISTS archive_common_invoices");
        jdbc.execute("DROP TABLE IF EXISTS common_invoice_orders");
        jdbc.execute("DROP TABLE IF EXISTS common_invoices");
        jdbc.execute("DROP TABLE IF EXISTS contractor_actual_payment_attributions");
        jdbc.execute("DROP TABLE IF EXISTS archive_payment_links");
        jdbc.execute("DROP TABLE IF EXISTS payment_links");
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE payment_links (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(32) NOT NULL,
                    payment_method VARCHAR(32) NOT NULL,
                    amount_kopecks BIGINT NOT NULL,
                    reserved_amount_kopecks BIGINT NULL,
                    confirmed_amount_kopecks BIGINT NULL,
                    manual_confirmed_at DATETIME(6) NULL,
                    paid_at DATETIME(6) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("CREATE TABLE archive_payment_links LIKE payment_links");
        jdbc.execute("""
                CREATE TABLE contractor_actual_payment_attributions (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    source_kind VARCHAR(32) NOT NULL,
                    source_id BIGINT NOT NULL,
                    evidence_id BIGINT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE common_invoices (
                    invoice_id BIGINT PRIMARY KEY,
                    payment_method VARCHAR(32) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE common_invoice_orders (
                    invoice_order_id BIGINT PRIMARY KEY,
                    invoice_id BIGINT NOT NULL,
                    amount_kopecks BIGINT NOT NULL,
                    paid BOOLEAN NOT NULL,
                    paid_at DATETIME(6) NULL,
                    payment_method VARCHAR(32) NULL,
                    source_payment_link_id BIGINT NULL,
                    manual_paid_by VARCHAR(160) NULL,
                    manual_payment_comment VARCHAR(1000) NULL,
                    manual_payment_receipt_url VARCHAR(1024) NULL,
                    actual_payment_evidence_reference VARCHAR(160) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE archive_common_invoices (
                    invoice_id BIGINT PRIMARY KEY,
                    payment_method VARCHAR(32) NULL,
                    restored_at DATETIME(6) NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("CREATE TABLE archive_common_invoice_orders LIKE common_invoice_orders");
    }
}
