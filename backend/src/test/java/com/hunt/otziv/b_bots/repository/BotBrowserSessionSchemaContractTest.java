package com.hunt.otziv.b_bots.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BotBrowserSessionSchemaContractTest {

    @Test
    void migrationEnforcesOneActiveLeaseAndNeverStoresTheVncCapability() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V1_10_188__bot_browser_sessions.sql"
        ).getContentAsString(StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        String normalizedSql = sql.replaceAll("\\s+", " ");

        assertThat(normalizedSql).contains(
                "session_id char(36)",
                "external_key_snapshot varchar(96) not null",
                "opener_username varchar(255) not null",
                "opener_subject varchar(512) not null",
                "status varchar(16) character set ascii collate ascii_bin not null",
                "when status in ('opening', 'open', 'closing', 'stop_retry') then bot_id",
                "unique key uk_bot_browser_sessions_active_bot (active_bot_id)",
                "check (status in ('opening', 'open', 'closing', 'stop_retry', 'closed'))",
                "heartbeat_expires_at <= absolute_expires_at",
                "stop_attempts >= 0 and version >= 0",
                "status <> 'open' or opened_at is not null",
                "status <> 'stop_retry' or next_stop_retry_at is not null",
                "status <> 'closed' or closed_at is not null",
                ") engine=innodb;"
        );
        assertThat(normalizedSql).doesNotContain("vnc_url", "vncurl");
    }
}
