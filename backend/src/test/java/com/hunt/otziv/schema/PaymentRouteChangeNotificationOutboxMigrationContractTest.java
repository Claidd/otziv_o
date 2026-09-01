package com.hunt.otziv.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PaymentRouteChangeNotificationOutboxMigrationContractTest {

    @Test
    void routeChangeDeliveryIsDurableUniqueAndLeaseFenced() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V1_10_279__payment_route_change_notification_outbox.sql"
        ).getContentAsString(StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("create table payment_route_change_notification_outbox")
                .contains("primary key (payment_link_id)")
                .contains("next_attempt_at datetime(6) not null default current_timestamp(6)")
                .contains("processing_token char(36)")
                .contains("processing_lease_until datetime(6)")
                .contains("sent_at datetime(6)")
                .contains("skipped_at datetime(6)")
                .contains("idx_payment_route_change_notification_due")
                .doesNotContain(
                        "copy_text",
                        "telegram_copy_transfer_number",
                        "delete from",
                        "drop table",
                        "drop column"
                );
    }
}
