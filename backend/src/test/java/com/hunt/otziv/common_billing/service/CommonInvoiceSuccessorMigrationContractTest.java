package com.hunt.otziv.common_billing.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CommonInvoiceSuccessorMigrationContractTest {

    @Test
    void migrationKeepsHistoryButAllowsExactlyOneActiveMembership() throws Exception {
        String resource = "db/migration/V1_10_240__common_invoice_successor_cycles.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("active_membership BOOLEAN NOT NULL DEFAULT TRUE"));
            assertTrue(sql.contains("CASE WHEN active_membership THEN order_id ELSE NULL END"));
            assertTrue(sql.contains("uk_common_invoice_active_order (active_order_id)"));
            assertTrue(sql.contains("DROP INDEX uk_common_invoice_order"));
            assertTrue(sql.contains("supersedes_invoice_id"));
            assertTrue(sql.contains("uk_common_invoice_cycle_idempotency"));
            assertTrue(sql.contains("fk_common_invoice_supersedes"));
        }
    }
}
