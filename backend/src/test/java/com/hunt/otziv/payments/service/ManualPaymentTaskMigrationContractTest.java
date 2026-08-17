package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ManualPaymentTaskMigrationContractTest {

    @Test
    void v252BridgesBothLegacySourceGenerationsWithoutCopyingArchivePii() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V1_10_252__manual_payment_task_actual_destination.sql"));

        assertTrue(sql.contains("CONCAT('LEGACY-', link.id)"));
        assertTrue(sql.contains("link.manual_task_generation = task.generation"));
        assertTrue(sql.contains("CONCAT('LEGACY-', invoice.invoice_id)"));
        assertTrue(sql.contains("invoice.payment_route_manual_task_generation = task.generation"));
        assertTrue(sql.contains("payment_route_manual_task_accounting_mode"));
        assertTrue(sql.contains("ALTER TABLE archive_common_invoices"));
        assertTrue(sql.contains("JOIN contractor_payment_allocations allocation"));
        assertTrue(sql.contains("allocation.mode"));
        assertTrue(sql.contains("JOIN manual_payment_tasks task"));
        assertTrue(sql.contains("manual_actual_recipient_frozen_at IS NOT NULL"));
        assertTrue(sql.contains("manual_actual_original_cash_destination_kind = CASE"));
        assertTrue(sql.contains("manual_actual_cash_destination_kind = CASE"));
        assertTrue(sql.contains("THEN 'CONTRACTOR_PROFILE'"));
        assertFalse(sql.toLowerCase().contains("alter table payment_link_archive"));
    }
}
