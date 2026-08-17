package com.hunt.otziv.client_messages.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BadReviewDeliveryMigrationContractTest {

    @Test
    void migrationAddsDurableUniqueDeliveryTokenAndRecoveryIndex() throws Exception {
        String resource = "db/migration/V1_10_241__bad_review_delivery_tokens.sql";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("delivery_token VARCHAR(64)"));
            assertTrue(sql.contains("UNIQUE INDEX uk_scheduled_message_delivery_token"));
            assertTrue(sql.contains("delivery_status VARCHAR(32)"));
            assertTrue(sql.contains("idx_scheduled_message_delivery_status"));
            assertTrue(sql.contains("idx_scheduled_message_scenario_error"));
        }
    }
}
