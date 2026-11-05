package com.hunt.otziv.whatsapp.service;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** V1_10_380 applies to the real schema shape and the invite check rows behave as the service expects. */
@Testcontainers
class WhatsAppInviteLinkCheckStoreMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("whatsapp_invite_checks").withUsername("root").withPassword("root");

    private JdbcTemplate jdbc;
    private WhatsAppInviteLinkCheckStore store;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS whatsapp_invite_link_checks");
        jdbc.execute("DROP TABLE IF EXISTS client_chat_messages");
        jdbc.execute("DROP TABLE IF EXISTS companies");
        jdbc.execute("CREATE TABLE companies (company_id BIGINT NOT NULL PRIMARY KEY)");
        jdbc.execute("CREATE TABLE client_chat_messages (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, message_text TEXT,"
                + " message_at DATETIME(6) NOT NULL)");
        jdbc.update("INSERT INTO companies VALUES (191), (192)");
        jdbc.update("INSERT INTO client_chat_messages (message_text, message_at) VALUES ('Сколько стоит?', NOW(6))");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_380__client_chat_message_edits_and_whatsapp_invite_checks.sql")).execute(jdbc.getDataSource());
        store = new WhatsAppInviteLinkCheckStore(jdbc);
    }

    @Test
    void migrationKeepsExistingMessagesUneditedAndStoresInviteChecks() {
        assertThat(jdbc.queryForObject("SELECT edit_count FROM client_chat_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT edited_text IS NULL AND edited_at IS NULL FROM client_chat_messages", Boolean.class)).isTrue();

        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 12, 0);
        store.suspect(191L, "g@g.us", "StoredCode1234", "https://chat.whatsapp.com/Current1234", "whatsapp_lika@t1", now);
        assertThat(store.all().get(191L).status()).isEqualTo(WhatsAppInviteLinkCheckStore.SUSPECTED);
        assertThat(store.stale()).isEmpty();

        store.observeAgain(191L, null, now.plusMinutes(30));
        assertThat(store.all().get(191L).currentInviteLink()).isEqualTo("https://chat.whatsapp.com/Current1234");

        store.confirm(191L, "g@g.us", "StoredCode1234", null, now.plusMinutes(31));
        var stale = store.stale();
        assertThat(stale).hasSize(1);
        assertThat(stale.getFirst().currentInviteLink()).isEqualTo("https://chat.whatsapp.com/Current1234");
        assertThat(stale.getFirst().confirmedAt()).isEqualTo(now.plusMinutes(31));
        assertThat(stale.getFirst().firstSeenAt()).isEqualTo(now);

        // A manual confirmation of a never-seen company starts directly as STALE.
        store.confirm(192L, "h@g.us", "OtherCode12345", "https://chat.whatsapp.com/Fresh123456", now);
        assertThat(store.stale()).hasSize(2);

        store.delete(191L);
        jdbc.update("DELETE FROM companies WHERE company_id = 192");
        assertThat(store.all()).isEmpty();
    }
}
