package com.hunt.otziv.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ManualPaymentTaskAccountingLedgerMigrationContractTest {

    @Test
    void v251QuarantinesLegacyTargetsAndCreatesAppendOnlyIdempotentLedger() throws Exception {
        String sql = migration().toUpperCase(Locale.ROOT);

        assertThat(sql).contains(
                "ACCOUNTING_TARGET_KIND VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED'",
                "NEEDS_RECONCILIATION",
                "MANUAL_PAYMENT_TASK_LEDGER_ENTRIES",
                "UK_MANUAL_TASK_LEDGER_OPERATION_SEQUENCE",
                "UK_MANUAL_TASK_LEDGER_RESERVATION_KEY",
                "LEGACY_BASELINE",
                "CONCAT('V251:BASELINE:', CONFIRMED.SOURCE_KIND, ':', CONFIRMED.SOURCE_ID)",
                "'PAYMENT_LINK' AS SOURCE_KIND",
                "'COMMON_INVOICE' AS SOURCE_KIND",
                "CONCAT('LEGACY-', CONFIRMED.SOURCE_ID)",
                "VERIFIED BOOLEAN NOT NULL",
                "ARCHIVE_PAYMENT_LINKS",
                "ARCHIVE_COMMON_INVOICES",
                "NOT EXISTS",
                "RESTORED_AT IS NULL"
        );
        assertThat(sql).doesNotContain("CONCAT('V251:BASELINE:TASK:'");
        assertThat(sql).doesNotContain(
                "SET ACCOUNTING_TARGET_KIND = 'OWNER'",
                "SET ACCOUNTING_TARGET_KIND = 'MANAGER'",
                "SET ACCOUNTING_TARGET_KIND = 'SPECIALIST'"
        );
    }

    @Test
    void legacyConfirmedBackfillKeepsExactSourceAmountsInsteadOfTaskAggregate() throws Exception {
        String sql = migration().toUpperCase(Locale.ROOT);

        assertThat(sql).contains(
                "LINK.STATUS = 'CONFIRMED'",
                "LINK.MANUAL_SOURCE = 'MANUAL_TASK'",
                "INVOICE.PAYMENT_ROUTE_MANUAL_SOURCE = 'MANUAL_TASK'",
                "CONFIRMED.CONFIRMED_KOPECKS",
                "'UNRESOLVED'",
                "FALSE"
        );
        assertThat(sql).doesNotContain("SUM(CONFIRMED.AMOUNT_KOPECKS)");
    }

    @Test
    void legacyBackfillNeverCopiesPlaintextIntoEncryptedSnapshotColumns() throws Exception {
        String sql = migration();

        assertThat(sql).contains(
                "'UNRESOLVED',\n       NULL,\n       NULL,\n       COALESCE(confirmed.manual_payment_type, task.manual_payment_type),\n       NULL,\n       NULL,\n       NULL",
                "'UNRESOLVED',\n       NULL,\n       NULL,\n       COALESCE(link.manual_payment_type, task.manual_payment_type),\n       NULL,\n       NULL,\n       NULL",
                "'UNRESOLVED',\n       NULL,\n       NULL,\n       COALESCE(invoice.payment_route_manual_type, task.manual_payment_type),\n       NULL,\n       NULL,\n       NULL"
        );
    }

    @Test
    void legacyPendingBackfillIncludesExpiredRowsForDeterministicRuntimeRelease() throws Exception {
        String sql = migration().toUpperCase(Locale.ROOT);

        assertThat(sql).contains(
                "LINK.STATUS IN ('WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED')",
                "CONCAT('PAYMENT_LINK:', LINK.ID, ':LEGACY-', LINK.ID)",
                "CONCAT('TASK:RELEASE:PAYMENT_LINK:', LINK.ID, ':LEGACY-', LINK.ID)",
                "WHERE LINK.MANUAL_SOURCE = 'MANUAL_TASK'",
                "WHERE INVOICE.PAYMENT_ROUTE_MANUAL_SOURCE = 'MANUAL_TASK'",
                "LINK.EXPIRES_AT <= CURRENT_TIMESTAMP(6)",
                "'SYSTEM:PAYMENT-ROUTING'",
                "'СРОК ДЕЙСТВИЯ РУЧНОЙ ПЛАТЕЖНОЙ ССЫЛКИ ИСТЕК'"
        );
        assertThat(sql).doesNotContain("LINK.EXPIRES_AT > CURRENT_TIMESTAMP(6)");
    }

    @Test
    void commonLegacyExposureIsArithmeticCheckedAndClosedTasksAreReopened() throws Exception {
        String sql = migration().toUpperCase(Locale.ROOT);

        assertThat(sql).contains(
                "INVOICE.PAYMENT_ROUTE_AMOUNT_KOPECKS <= INVOICE.AMOUNT_KOPECKS",
                "INVOICE.PAID_KOPECKS >= GREATEST(",
                "INVOICE.AMOUNT_KOPECKS - INVOICE.PAYMENT_ROUTE_AMOUNT_KOPECKS",
                "SET INVOICE.STATUS = 'NEEDS_ATTENTION'",
                "TASK.STATUS = 'NEEDS_ATTENTION'",
                "TASK.NEEDS_RECONCILIATION = TRUE",
                "TASK.STATUS IN ('COMPLETED', 'CANCELED')",
                "HAVING SUM(SOURCE_ENTRY.RESERVED_DELTA_KOPECKS) > 0"
        );
    }

    private String migration() throws Exception {
        return new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                        "db/migration/V1_10_251__manual_payment_task_accounting_ledger.sql"
                )).readAllBytes(),
                StandardCharsets.UTF_8
        );
    }
}
