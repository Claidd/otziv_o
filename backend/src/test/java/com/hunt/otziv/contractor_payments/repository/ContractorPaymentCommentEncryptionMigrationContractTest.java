package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContractorPaymentCommentEncryptionMigrationContractTest {

    @Test
    void migrationWidensBothCommentColumnsForAuthenticatedEncryption() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V1_10_226__encrypt_contractor_payment_comments.sql"
        )) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("contractor_payment_profiles")
                .contains("payment_comment VARCHAR(2048)")
                .contains("contractor_payment_allocations")
                .contains("payment_comment_snapshot VARCHAR(2048)");
    }
}
