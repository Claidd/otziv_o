package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OwnerManualCardPaymentApprovalMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_272__owner_manual_card_payment_approval.sql";

    @Test
    void createsOneDurableAuditedApprovalPerPaymentLinkWithoutDeletingPaymentHistory() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("create table owner_manual_card_payment_approvals")
                .contains("unique key uk_owner_manual_card_approval_link (payment_link_id)")
                .contains("callback_token_hash char(64) not null")
                .contains("status varchar(24) not null")
                .contains("approved_by_user_id bigint null")
                .contains("approved_at datetime(6) null")
                .doesNotContain("delete from payment_links")
                .doesNotContain("update payment_links")
                .doesNotContain("on delete cascade");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }
}
