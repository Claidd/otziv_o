package com.hunt.otziv.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentReturnManualResolutionMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_283__payment_return_manual_resolution.sql";

    @Test
    void migrationMirrorsAuditAndTupleChecksAcrossLiveAndArchive() throws IOException {
        String sql;
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IOException("Missing migration " + MIGRATION);
            }
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(sql.contains("return_recovery_resolved_at datetime(6) null"));
        assertTrue(sql.contains("return_recovery_resolved_by varchar(150) null"));
        assertTrue(sql.contains("return_recovery_resolution_reason varchar(512) null"));
        assertTrue(sql.contains("ck_payment_check_return_snapshot"));
        assertTrue(sql.contains("ck_archive_payment_check_return_snapshot"));
        assertTrue(sql.contains("check_paid_amount is null or check_paid_amount >= 0"));
        assertTrue(sql.contains("check_payment_link is null or check_paid_amount is not null"));
        assertTrue(sql.contains("ck_payment_links_return_recovery_tuple"));
        assertTrue(sql.contains("ck_archive_payment_links_return_recovery_tuple"));
        assertTrue(sql.contains("applied_manually"));
        assertTrue(sql.contains("accepted_noop"));
        assertTrue(sql.contains("information_schema.table_constraints"));
        assertTrue(sql.contains("@v283_constraint_exists = 0"));
        assertTrue(sql.contains("update payment_link_return_reconciliation_outbox return_outbox"));
        assertTrue(sql.contains("return_outbox.status = 'succeeded'"));
        assertTrue(sql.contains("insert ignore into payment_link_return_reconciliation_outbox"));
        assertTrue(sql.contains("link.status = 'canceled'"));
        assertTrue(sql.contains("confirmed_amount_kopecks"));
        assertTrue(sql.contains("bank_cancel_origin_status"));
        assertTrue(sql.contains("update archive_payment_links archived_link"));
        assertFalse(sql.contains("archived_link.bank_cancel_origin_status"));
        assertTrue(sql.contains("archived_payment_return_manual_reconciliation:v283_missing_financial_cycle_attribution"));
        assertTrue(sql.contains("archived_payment_return_reconciliation_required"));
        assertTrue(sql.contains("insert into business_audit_events"));
        assertTrue(sql.contains("existing.entity_id collate utf8mb4_unicode_ci"));
        assertTrue(sql.contains(
                "cast(archived_link.id as char character set utf8mb4) collate utf8mb4_unicode_ci"));
        assertTrue(sql.contains("no financial mutation was attempted"));
    }
}
