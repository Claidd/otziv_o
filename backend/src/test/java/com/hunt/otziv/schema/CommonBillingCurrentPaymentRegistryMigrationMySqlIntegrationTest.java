package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class CommonBillingCurrentPaymentRegistryMigrationMySqlIntegrationTest {

    private static final String MYSQL_IMAGE =
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("common_payment_registry_contract")
            .withUsername("root")
            .withPassword("root");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        initializeSchema();
        seedLegacyRows();
    }

    @Test
    void migrationIsFailClosedIdempotentAndEnforcesOneCurrentPerInvoice() {
        assertThat(jdbc.queryForObject(
                "SELECT DEFAULT_COLLATION_NAME FROM INFORMATION_SCHEMA.SCHEMATA "
                        + "WHERE SCHEMA_NAME = DATABASE()",
                String.class
        )).isEqualTo("utf8mb4_unicode_ci");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME IN ('common_invoices', 'common_invoice_payment_refs') "
                        + "AND COLUMN_NAME IN ('tbank_order_id', 'tbank_payment_id') "
                        + "AND COLLATION_NAME = 'utf8mb4_0900_ai_ci'",
                Integer.class
        )).isEqualTo(4);

        runMigration();

        assertRef(1L, "CURRENT", "order-1", "payment-1");
        assertRef(2L, "APPLIED", "order-2", "payment-2");
        assertThat(value("SELECT tbank_order_id FROM common_invoices WHERE invoice_id = 2"))
                .isNull();
        assertThat(value("SELECT tbank_payment_id FROM common_invoices WHERE invoice_id = 2"))
                .isNull();
        assertThat(value("SELECT payment_url FROM common_invoices WHERE invoice_id = 2"))
                .isNull();

        assertInvoiceNeedsAttention(3L);
        assertRef(3L, "INIT_CONFLICT", "order-3", null);
        assertInvoiceNeedsAttention(4L);
        assertInvoiceNeedsAttention(5L);
        assertInvoiceNeedsAttention(6L);
        assertInvoiceNeedsAttention(7L);
        assertInvoiceNeedsAttention(8L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM common_invoice_payment_refs "
                        + "WHERE invoice_id = 8 AND status = 'INIT_CONFLICT'",
                Integer.class
        )).isEqualTo(2);

        assertRef(9L, "CURRENT", "order-9", "payment-9");
        assertInvoiceNeedsAttention(10L);
        assertInvoiceNeedsAttention(11L);
        assertInvoiceNeedsAttention(12L);
        assertRef(12L, "CANCELED", "order-12", "payment-12");
        assertInvoiceNeedsAttention(13L);
        assertInvoiceNeedsAttention(14L);
        assertRef(14L, "INIT_CONFLICT", "order-14", "payment-14");
        assertRef(15L, "APPLIED", "order-15", "payment-15");
        assertThat(value("SELECT tbank_order_id FROM common_invoices WHERE invoice_id = 15"))
                .isNull();
        assertInvoiceNeedsAttention(16L);
        assertInvoiceNeedsAttention(17L);
        assertRef(17L, "CANCEL_PENDING", "old-order-17", "old-payment-17");
        assertInvoiceNeedsAttention(18L);
        assertRef(18L, "INIT_CONFLICT", "registry-order-18", "registry-payment-18");
        assertInvoiceNeedsAttention(19L);
        assertRef(19L, "AUTHORIZED", "old-order-19", "old-payment-19");
        assertInvoiceNeedsAttention(20L);
        assertInvoiceNeedsAttention(21L);
        assertInvoiceNeedsAttention(22L);
        assertInvoiceNeedsAttention(23L);
        assertThat(value("SELECT last_error FROM common_invoices WHERE invoice_id = 22"))
                .isEqualTo("migration_common_payment_registry:"
                        + "x".repeat(160)
                        + "; provider evidence preserved; manual reconciliation required");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'common_invoice_payment_refs' "
                        + "AND INDEX_NAME IN ("
                        + "'uk_common_invoice_payment_ref_order', "
                        + "'uk_common_invoice_payment_ref_payment') "
                        + "AND NON_UNIQUE = 0",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT GENERATION_EXPRESSION FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'common_invoice_payment_refs' "
                        + "AND COLUMN_NAME = 'current_invoice_id'",
                String.class
        )).containsIgnoringCase("status")
                .containsIgnoringCase("CURRENT")
                .containsIgnoringCase("invoice_id");

        int refCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM common_invoice_payment_refs",
                Integer.class
        );
        jdbc.execute("""
                ALTER TABLE common_invoice_payment_refs
                    DROP INDEX uk_common_invoice_payment_refs_current_invoice,
                    DROP INDEX idx_common_invoice_payment_refs_invoice_status_updated
                """);
        runMigration();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM common_invoice_payment_refs",
                Integer.class
        )).isEqualTo(refCount);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'common_invoice_payment_refs' "
                        + "AND INDEX_NAME IN ("
                        + "'uk_common_invoice_payment_refs_current_invoice', "
                        + "'idx_common_invoice_payment_refs_invoice_status_updated')",
                Integer.class
        )).isEqualTo(2);
        runMigration();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM common_invoice_payment_refs",
                Integer.class
        )).isEqualTo(refCount);
        assertRef(1L, "CURRENT", "order-1", "payment-1");
        assertInvoiceNeedsAttention(3L);

        jdbc.update("""
                INSERT INTO common_invoice_payment_refs (
                    invoice_id, tbank_order_id, tbank_payment_id, status
                ) VALUES (9, 'historical-9', 'historical-payment-9', 'ARCHIVED')
                """);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO common_invoice_payment_refs (
                    invoice_id, tbank_order_id, tbank_payment_id, status
                ) VALUES (9, 'second-current-9', 'second-current-payment-9', 'CURRENT')
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_common_invoice_payment_refs_current_invoice");
    }

    @Test
    void migrationRejectsWrongPreexistingGeneratedColumnShape() {
        jdbc.execute("ALTER TABLE common_invoice_payment_refs "
                + "ADD COLUMN current_invoice_id BIGINT NULL");

        assertThatThrownBy(this::runMigration)
                .hasStackTraceContaining("__V200_INVALID_CURRENT_INVOICE_ID_GUARD");
    }

    @Test
    void migrationRejectsWrongPreexistingUniqueIndexShape() {
        jdbc.execute("ALTER TABLE common_invoice_payment_refs "
                + "ADD COLUMN current_invoice_id BIGINT GENERATED ALWAYS AS "
                + "(CASE WHEN status = 'CURRENT' THEN invoice_id ELSE NULL END) VIRTUAL");
        jdbc.execute("ALTER TABLE common_invoice_payment_refs "
                + "ADD UNIQUE KEY uk_common_invoice_payment_refs_current_invoice (payment_ref_id)");

        assertThatThrownBy(this::runMigration)
                .hasStackTraceContaining("__V200_INVALID_CURRENT_INVOICE_UNIQUE_GUARD");
    }

    @Test
    void migrationRejectsWrongPreexistingLookupIndexShape() {
        jdbc.execute("ALTER TABLE common_invoice_payment_refs "
                + "ADD INDEX idx_common_invoice_payment_refs_invoice_status_updated "
                + "(status, invoice_id, updated_at, payment_ref_id)");

        assertThatThrownBy(this::runMigration)
                .hasStackTraceContaining("__V200_INVALID_CURRENT_INVOICE_LOOKUP_GUARD");
    }

    @Test
    void migrationAcceptsEquivalentGeneratedColumnFromLegacyConnectionCharset() {
        jdbc.execute("ALTER TABLE common_invoice_payment_refs "
                + "ADD COLUMN current_invoice_id BIGINT GENERATED ALWAYS AS "
                + "(CASE WHEN status = _latin1'CURRENT' THEN invoice_id ELSE NULL END) VIRTUAL");

        runMigration();
        runMigration();

        assertRef(1L, "CURRENT", "order-1", "payment-1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'common_invoice_payment_refs' "
                        + "AND INDEX_NAME IN ("
                        + "'uk_common_invoice_payment_refs_current_invoice', "
                        + "'idx_common_invoice_payment_refs_invoice_status_updated')",
                Integer.class
        )).isEqualTo(2);
    }

    private void initializeSchema() {
        jdbc.execute("DROP TABLE IF EXISTS common_invoice_payment_refs");
        jdbc.execute("DROP TABLE IF EXISTS common_invoices");
        jdbc.execute("""
                CREATE TABLE common_invoices (
                    invoice_id BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    tbank_order_id VARCHAR(36) NULL,
                    tbank_payment_id VARCHAR(64) NULL,
                    tbank_terminal_key VARCHAR(64) NULL,
                    tbank_payment_amount_kopecks BIGINT NULL,
                    tbank_payment_created_at DATETIME(6) NULL,
                    payment_url VARCHAR(1024) NULL,
                    last_error VARCHAR(512) NULL,
                    next_reminder_at DATETIME(6) NULL,
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (invoice_id),
                    UNIQUE KEY uk_common_invoices_tbank_order_id (tbank_order_id),
                    INDEX idx_common_invoices_tbank_payment_id (tbank_payment_id)
                ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE common_invoice_payment_refs (
                    payment_ref_id BIGINT NOT NULL AUTO_INCREMENT,
                    invoice_id BIGINT NOT NULL,
                    tbank_order_id VARCHAR(36) NULL,
                    tbank_payment_id VARCHAR(64) NULL,
                    tbank_terminal_key VARCHAR(64) NULL,
                    amount_kopecks BIGINT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'ARCHIVED',
                    reason VARCHAR(160) NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
                    cancel_attempts INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (payment_ref_id),
                    UNIQUE KEY uk_common_invoice_payment_ref_order (tbank_order_id),
                    UNIQUE KEY uk_common_invoice_payment_ref_payment (tbank_payment_id),
                    INDEX idx_common_invoice_payment_refs_invoice (invoice_id),
                    CONSTRAINT fk_common_invoice_payment_refs_invoice
                        FOREIGN KEY (invoice_id) REFERENCES common_invoices (invoice_id)
                ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                ALTER DATABASE common_payment_registry_contract
                    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                """);
    }

    private void seedLegacyRows() {
        insertInvoice(1, "INVOICED", "order-1", "payment-1", "terminal", 100,
                "http://localhost/pay/1", null);
        insertInvoice(2, "PAID", "order-2", "payment-2", "terminal", 200,
                "javascript:alert(1)", null);
        insertRef(2, "order-2", "payment-2", "terminal", 200, "APPLIED");

        insertInvoice(3, "INVOICED", "order-3", null, "terminal", 300,
                null, "payment_init_in_progress");
        insertInvoice(4, "INVOICED", "order-4", "duplicate-payment", "terminal", 400,
                "https://bank.example/pay/4", null);
        insertInvoice(5, "INVOICED", "order-5", "duplicate-payment", "terminal", 500,
                "https://bank.example/pay/5", null);

        insertInvoice(6, "INVOICED", "foreign-order", "payment-6", "terminal", 600,
                "https://bank.example/pay/6", null);
        insertInvoice(7, "READY", null, null, null, null, null, null);
        insertRef(7, "foreign-order", "payment-7", "terminal", 700, "ARCHIVED");

        insertInvoice(8, "INVOICED", "order-8-a", "payment-8-a", "terminal", 800,
                "https://bank.example/pay/8", null);
        insertRef(8, "order-8-a", "payment-8-a", "terminal", 800, "CURRENT");
        insertRef(8, "order-8-b", "payment-8-b", "terminal", 800, "CURRENT");

        insertInvoice(9, "INVOICED", "order-9", "payment-9", "terminal", 900,
                "https://bank.example/pay/9", null);
        insertRef(9, "order-9", "payment-9", "terminal", 900, "CURRENT");

        insertInvoice(10, "INVOICED", "order-10", "payment-10", "terminal", 1000,
                null, null);
        insertInvoice(11, "INVOICED", "order-11", "payment-11", "terminal", 1100,
                "javascript:alert(1)", null);

        insertInvoice(12, "INVOICED", "order-12", "payment-12", "terminal", 1200,
                "https://bank.example/pay/12", null);
        insertRef(12, "order-12", "payment-12", "terminal", 1200, "CANCELED");

        insertInvoice(13, "ARCHIVED", "order-13", "payment-13", "terminal", 1300,
                "https://bank.example/pay/13", null);
        insertInvoice(14, "INVOICED", "order-14", "payment-14", "terminal", 1400,
                "https://bank.example/pay/14", "payment_init_in_progress");
        insertInvoice(15, "PAID", "order-15", "payment-15", "terminal", 1500,
                "https://bank.example/pay/15", null);
        insertInvoice(16, "INVOICED", "order-16", "payment-16", "terminal", 1600,
                "https://user@bank.example/pay/16", null);
        insertInvoice(17, "INVOICED", "order-17", "payment-17", "terminal", 1700,
                "https://bank.example/pay/17", null);
        insertRef(17, "old-order-17", "old-payment-17", "terminal", 1700, "CANCEL_PENDING");
        insertInvoice(18, "PAID", null, null, null, null, null, null);
        insertRef(18, "registry-order-18", "registry-payment-18", "terminal", 1800, "CURRENT");
        insertInvoice(19, "INVOICED", "order-19", "payment-19", "terminal", 1900,
                "https://bank.example/pay/19", null);
        insertRef(19, "old-order-19", "old-payment-19", "terminal", 1900, "AUTHORIZED");
        insertInvoice(20, "PAID", null, null, null, null,
                "https://bank.example/pay/20", null);
        insertInvoice(21, "INVOICED", "order-21", "payment-21", "terminal", 2100,
                "https://bank.example/" + "😀".repeat(502), null);
        insertInvoice(22, "READY", null, null, null, null, null,
                "migration_common_payment_registry:" + "x".repeat(200) + ";legacy-tail");
        insertInvoice(23, "INVOICED", "order-23", "payment-23", "terminal", 2300,
                "https://bank.example/{bad}", null);
    }

    private void insertInvoice(
            long id,
            String status,
            String orderId,
            String paymentId,
            String terminal,
            Integer amount,
            String paymentUrl,
            String lastError
    ) {
        jdbc.update("""
                INSERT INTO common_invoices (
                    invoice_id, status, tbank_order_id, tbank_payment_id,
                    tbank_terminal_key, tbank_payment_amount_kopecks,
                    tbank_payment_created_at, payment_url, last_error,
                    next_reminder_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), ?, ?, CURRENT_TIMESTAMP(6))
                """, id, status, orderId, paymentId, terminal, amount, paymentUrl, lastError);
    }

    private void insertRef(
            long invoiceId,
            String orderId,
            String paymentId,
            String terminal,
            Integer amount,
            String status
    ) {
        jdbc.update("""
                INSERT INTO common_invoice_payment_refs (
                    invoice_id, tbank_order_id, tbank_payment_id,
                    tbank_terminal_key, amount_kopecks, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, invoiceId, orderId, paymentId, terminal, amount, status);
    }

    private void runMigration() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(
                        "db/migration/V1_10_200__common_billing_current_payment_registry.sql"
                )
        );
        populator.execute(dataSource);
    }

    private void assertInvoiceNeedsAttention(long invoiceId) {
        assertThat(value("SELECT status FROM common_invoices WHERE invoice_id = " + invoiceId))
                .isEqualTo("NEEDS_ATTENTION");
        assertThat(value("SELECT payment_url FROM common_invoices WHERE invoice_id = " + invoiceId))
                .isNull();
        assertThat(value("SELECT next_reminder_at FROM common_invoices WHERE invoice_id = " + invoiceId))
                .isNull();
    }

    private void assertRef(
            long invoiceId,
            String status,
            String orderId,
            String paymentId
    ) {
        assertThat(jdbc.queryForList(
                "SELECT status, tbank_order_id, tbank_payment_id "
                        + "FROM common_invoice_payment_refs WHERE invoice_id = ?",
                invoiceId
        )).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo(status);
            assertThat(row.get("tbank_order_id")).isEqualTo(orderId);
            assertThat(row.get("tbank_payment_id")).isEqualTo(paymentId);
        });
    }

    private Object value(String sql) {
        return jdbc.queryForList(sql).getFirst().values().iterator().next();
    }
}
