package com.hunt.otziv.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentReturnRecoveryCleanupMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_284__exclude_test_returns_and_close_resolved_recovery.sql";

    @Test
    void migrationClosesOnlyEvidenceBackedFalseAlertsWithoutFinancialMutation() throws IOException {
        String sql;
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IOException("Missing migration " + MIGRATION);
            }
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertFalse(sql.contains("payment_profiles"));
        assertFalse(sql.contains("test_mode"));
        assertTrue(sql.contains("coalesce(link.tbank_terminal_key, '')"));
        assertTrue(sql.contains("coalesce(archived_link.tbank_terminal_key, '')"));
        assertTrue(sql.contains("= 'demo'"));
        assertTrue(sql.contains("bank_cancel_origin_status = 'test_confirmed'"));
        assertTrue(sql.contains("payment_method in ('bank_form', 'sbp_qr')"));
        assertTrue(sql.contains("return_recovery_outcome = 'manual_reconciliation'"));
        assertTrue(sql.contains("last_error like 'payment_return_manual_reconciliation:%'"));

        assertTrue(sql.contains("proof.created_at = timestamp('2026-08-29 10:52:48.613881')"));
        assertTrue(sql.contains("proof.actor = 'owner:hunt'"));
        assertTrue(sql.contains("proof.source = 'owner_confirmed_tbank_refund'"));
        assertTrue(sql.contains("proof.action = 'order_duplicate_payment_refunded'"));
        assertTrue(sql.contains("proof.entity_type = 'payment_link'"));
        assertTrue(sql.contains("proof.entity_id = '3918'"));
        assertTrue(sql.contains("proof.order_id = 24378"));
        assertTrue(sql.contains(
                "cash=200000;check=200000;adjustment=-100000;link=amount_mismatch"));
        assertTrue(sql.contains(
                "cash=100000;check=100000;adjustment=inactive;link=refunded"));
        assertFalse(sql.contains("proof.details like"));

        assertTrue(sql.contains("v284_production_refund_guard"));
        assertFalse(sql.contains("start transaction"));
        assertFalse(sql.contains("commit;"));
        assertTrue(sql.contains("for update"));
        assertTrue(sql.contains("for share"));
        assertTrue(sql.contains("payment.check_id = 20240"));
        assertTrue(sql.contains("primary_payment.id = 3815"));
        assertTrue(sql.contains("base_order.order_status = 10"));
        assertTrue(sql.contains("d09ed0bfd0bbd0b0d187d0b5d0bdd0be"));
        assertTrue(sql.contains("nullif(trim(returned_payment.tbank_terminal_key), '') is not null"));
        assertTrue(sql.contains("reconciliation.active = 0"));
        assertTrue(sql.contains("set link.return_recovery_outcome = 'accepted_noop'"));
        assertTrue(sql.contains("return_recovery_resolved_by = 'system:migration:v284'"));
        assertTrue(sql.contains("return_recovery_resolution_reason = candidate.resolution_reason"));
        assertTrue(sql.contains("link.return_recovery_payment_check_id <=>"));
        assertFalse(sql.contains("return_recovery_processed_at = null"));
        assertFalse(sql.contains("return_recovery_payment_check_id = null"));

        assertTrue(sql.contains("update personal_reminders reminder"));
        assertTrue(sql.contains("candidate.order_id = reminder.source_order_id"));
        assertTrue(sql.contains("reminder.completed_at = coalesce"));
        assertTrue(sql.contains("@v284_completed_reminder_count = row_count()"));
        assertTrue(sql.contains("sum(candidate.open_reminder_count)"));
        assertTrue(sql.contains("payment_return_test_recovery_ignored"));
        assertTrue(sql.contains("payment_return_manual_reconciliation_resolved"));
        assertTrue(sql.contains("insert into v284_resolved_archived_return_recovery"));
        assertTrue(sql.contains("archived_payment_return_manual_reconciliation:v283_missing_financial_cycle_attribution"));
        assertTrue(sql.contains("archived_payment_return_reconciliation_required"));
        assertTrue(sql.contains("archived_payment_return_test_recovery_ignored"));
        assertTrue(sql.contains("update archive_payment_links archived_link"));
        assertTrue(sql.contains("insert into business_audit_events"));
        assertTrue(sql.contains("where not exists"));
        assertTrue(sql.contains("no order, payment_check, company or reward-ledger mutation was performed"));

        assertFalse(sql.contains("update orders "));
        assertFalse(sql.contains("update payment_check "));
        assertFalse(sql.contains("update companies "));
        assertFalse(sql.contains("update zp "));
        assertFalse(sql.contains("update contractor_reward_ledger "));
        assertFalse(sql.contains("update payment_link_return_reconciliation_outbox "));
        assertFalse(sql.contains("delete from personal_reminders"));
    }
}
