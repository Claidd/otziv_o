package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PaymentSuccessNotificationRetryClaimMigrationContractTest {

    @Test
    void migrationAddsAnIsolatedFencedLeaseWithCascadeCleanup() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V1_10_198__payment_success_notification_retry_claims.sql"
        ).getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql)
                .contains("create table payment_success_notification_retry_claims")
                .contains("processing_token char(36)")
                .contains("processing_lease_until datetime(6)")
                .contains("foreign key (payment_link_id) references payment_links (id)")
                .contains("on delete cascade")
                .doesNotContain("alter table payment_links")
                .doesNotContain("payload");
    }
}
