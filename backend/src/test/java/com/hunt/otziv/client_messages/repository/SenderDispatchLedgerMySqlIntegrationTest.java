package com.hunt.otziv.client_messages.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.client_messages.service.SenderAccountPacing;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** The production migration and SQL behind the per-account interval shared by campaigns and the autoresponder. */
@Testcontainers
class SenderDispatchLedgerMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("sender_ledger_fixture").withUsername("root").withPassword(UUID.randomUUID().toString());

    private final LocalDateTime t0 = LocalDateTime.of(2026, 11, 4, 11, 0, 0, 123_456_000);
    private JdbcTemplate jdbc;
    private SenderDispatchLedger ledger;
    private SenderAccountPacing pacing;

    @BeforeEach
    void prepare() {
        var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(data);
        jdbc.execute("DROP TABLE IF EXISTS client_message_sender_dispatches");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_377__client_message_sender_dispatches.sql")).execute(data);
        // Flyway re-validates only once; the migration itself must also be safe to re-apply.
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_10_377__client_message_sender_dispatches.sql")).execute(data);
        ledger = new SenderDispatchLedger(new NamedParameterJdbcTemplate(jdbc));
        pacing = new SenderAccountPacing(ledger);
    }

    @Test
    void latestIsKeptPerEventAndLaneAndPerAccount() {
        ledger.record("WHATSAPP:lika", "CAMPAIGN", "SENT", t0);
        ledger.record("WHATSAPP:lika", "CAMPAIGN", "SENT", t0.plusMinutes(5));
        ledger.record("WHATSAPP:lika", "AUTORESPONDER", "WAITING", t0.plusMinutes(1));
        ledger.record("WHATSAPP:vika", "AUTORESPONDER", "SENT", t0.plusMinutes(9));

        assertThat(ledger.latest("WHATSAPP:lika"))
                .containsEntry("SENT:CAMPAIGN", t0.plusMinutes(5))
                .containsEntry("WAITING:AUTORESPONDER", t0.plusMinutes(1))
                .hasSize(2);
        assertThat(ledger.latest("TELEGRAM")).isEmpty();
    }

    @Test
    void pacingAlternatesThroughRealRowsAndPurgesOldOnes() {
        ledger.record("WHATSAPP:lika", "CAMPAIGN", "SENT", t0.minusDays(5));
        assertThat(pacing.admit(SenderAccountPacing.Lane.AUTORESPONDER, "WHATSAPP:lika", 180, t0).allowed()).isTrue();
        assertThat(pacing.admit(SenderAccountPacing.Lane.CAMPAIGN, "WHATSAPP:lika", 180, t0.plusSeconds(20)).hold())
                .isEqualTo(SenderAccountPacing.Hold.INTERVAL);
        assertThat(pacing.admit(SenderAccountPacing.Lane.AUTORESPONDER, "WHATSAPP:lika", 180, t0.plusSeconds(181)).hold())
                .isEqualTo(SenderAccountPacing.Hold.TURN);
        assertThat(pacing.admit(SenderAccountPacing.Lane.CAMPAIGN, "WHATSAPP:lika", 180, t0.plusSeconds(185)).allowed()).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM client_message_sender_dispatches WHERE occurred_at < ?",
                Integer.class, t0.minusDays(3))).isZero();
        assertThat(jdbc.queryForList("SELECT CONCAT(lane, ':', event) FROM client_message_sender_dispatches ORDER BY id", String.class))
                .containsExactly("AUTORESPONDER:SENT", "CAMPAIGN:WAITING", "AUTORESPONDER:WAITING", "CAMPAIGN:SENT");
    }
}
