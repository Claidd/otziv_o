package com.hunt.otziv.client_messages.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PaymentOverdueDaysMigrationContractTest {

    @Test
    void migrationChangesOnlyFormerSixtyDayDefaultAndSeedsMissingSetting() throws Exception {
        String resource = "db/migration/V1_10_248__payment_overdue_thirty_days.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("TRIM(setting_value) = '60'"));
            assertTrue(sql.contains("setting_value = '30'"));
            assertTrue(sql.contains("WHERE NOT EXISTS"));
        }
    }
}
