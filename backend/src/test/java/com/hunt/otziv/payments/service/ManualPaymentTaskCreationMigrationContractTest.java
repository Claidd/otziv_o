package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ManualPaymentTaskCreationMigrationContractTest {

    @Test
    void v253KeepsTaskCreationExactlyOnceAndCommonReceiptEvidenceArchiveSafe() throws Exception {
        String sql = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                        "db/migration/V1_10_253__manual_payment_task_creation_and_receipt_evidence.sql"
                )).readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertTrue(sql.contains("v253_common_attribution_preflight"));
        assertTrue(sql.contains("source_kind = 'COMMON_INVOICE'"));
        assertTrue(sql.contains("CHECK (existing_count = 0)"));
        assertTrue(sql.contains("CREATE TABLE manual_payment_task_creation_requests"));
        assertTrue(sql.contains("PRIMARY KEY (operation_key)"));
        assertTrue(sql.contains("payload_hash CHAR(64) NOT NULL"));
        assertTrue(sql.contains("task_id BIGINT NULL"));
        assertTrue(sql.contains("ALTER TABLE common_invoice_orders"));
        assertTrue(sql.contains("ALTER TABLE archive_common_invoice_orders"));
        assertTrue(sql.contains("actual_payment_evidence_reference VARCHAR(160) NULL"));
        assertTrue(sql.contains("idx_common_invoice_order_actual_evidence"));
        assertTrue(sql.contains("idx_archive_common_invoice_order_actual_evidence"));
        assertTrue(sql.contains("idx_common_invoice_order_manual_month"));
        assertTrue(sql.contains("idx_archive_common_invoice_order_manual_month"));
    }
}
