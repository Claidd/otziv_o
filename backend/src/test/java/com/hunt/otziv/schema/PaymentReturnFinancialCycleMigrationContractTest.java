package com.hunt.otziv.schema;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PaymentReturnFinancialCycleMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_282__bind_payment_returns_to_financial_cycle.sql";

    @Test
    void migrationKeepsSnapshotsNullableAndMirrorsEveryArchiveColumn() throws IOException {
        String sql;
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IOException("Missing migration " + MIGRATION);
            }
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(sql.contains("alter table payment_check"));
        assertTrue(sql.contains("alter table archive_payment_check"));
        assertTrue(sql.contains("check_paid_amount int null"));
        assertTrue(sql.contains("check_payment_link bigint null"));
        assertTrue(sql.contains("alter table payment_links"));
        assertTrue(sql.contains("alter table archive_payment_links"));
        assertTrue(sql.contains("return_recovery_processed_at datetime(6) null"));
        assertTrue(sql.contains("return_recovery_payment_check_id bigint null"));
        assertTrue(sql.contains("return_recovery_outcome varchar(32) null"));
        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("@v282_column_exists = 0"));
        assertTrue(!sql.contains("foreign key"));
    }
}
