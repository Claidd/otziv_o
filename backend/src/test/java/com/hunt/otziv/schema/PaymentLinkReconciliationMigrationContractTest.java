package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PaymentLinkReconciliationMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_189__payment_link_reconciliation_attempts.sql";

    @Test
    void reconciliationRotationUsesAnAdditiveOnlineColumnAndDueIndex() throws Exception {
        String sql = new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "ALTER TABLE payment_links",
                "ADD COLUMN bank_reconciliation_attempted_at DATETIME(6) NULL",
                "ADD INDEX idx_payment_links_bank_reconciliation_due",
                "(status, bank_reconciliation_attempted_at, updated_at, id)",
                "ALGORITHM=INPLACE",
                "LOCK=NONE"
        );
        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("insert ", "update ", "delete ", "drop ");
    }
}
