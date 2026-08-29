package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CommonInvoicePaymentNotificationOutboxMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V1_10_273__common_invoice_payment_notification_outbox.sql";

    @Test
    void createsFencedIdempotentClientAndRecipientDeliveryRows() throws Exception {
        String sql = migration();

        assertThat(sql)
                .contains("create table common_invoice_payment_notification_outbox")
                .contains("unique key uq_common_invoice_notification")
                .contains("unique key uq_common_invoice_recipient_attribution")
                .contains("processing_token char(36)")
                .contains("processing_lease_until datetime(6)")
                .contains("notification_kind in ('client', 'recipient')")
                .contains("attribution.accounting_mode = 'live'")
                .contains("invoice.payment_success_notified_at is null")
                .doesNotContain("delete from common_invoices")
                .doesNotContain("update common_invoices");
    }

    private String migration() throws Exception {
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }
}
