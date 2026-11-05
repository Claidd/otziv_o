package com.hunt.otziv.notification_media.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** The account-blocker notices are once per day and live outside the motivational daily cap. */
@Testcontainers
class ThematicNotificationDispatchStoreMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("thematic_dispatch").withUsername("root").withPassword(UUID.randomUUID().toString());

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    private static final String BLOCKERS = NotificationMediaEventCatalog.WORKER_ACCOUNT_BLOCKED_TASKS.code();

    private JdbcTemplate jdbc;
    private ThematicNotificationDispatchStore store;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS thematic_notification_dispatches");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("""
                CREATE TABLE thematic_notification_dispatches (
                    dispatch_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    event_code VARCHAR(80) NOT NULL, recipient_user_id BIGINT NOT NULL,
                    dispatch_date DATE NOT NULL, status VARCHAR(16) NOT NULL, sent_at DATETIME(6) NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    UNIQUE KEY uk_thematic_dispatch_event_user_date (event_code, recipient_user_id, dispatch_date)
                ) ENGINE=InnoDB""");
        jdbc.update("INSERT INTO users VALUES (60), (61)");
        store = new ThematicNotificationDispatchStore(new NamedParameterJdbcTemplate(ds));
    }

    @Test
    void blockerNoticeIsOncePerDayAndIgnoresTheMotivationalCap() {
        assertThat(store.claim("WORKER_DAY_START", 60L, DAY, 1)).isTrue();
        assertThat(store.claim("WORKER_SITE_INACTIVE", 60L, DAY, 1)).isFalse();

        assertThat(store.claim(BLOCKERS, 60L, DAY, 1)).isTrue();
        store.markSent(BLOCKERS, 60L, DAY);
        assertThat(store.claim(BLOCKERS, 60L, DAY, Integer.MAX_VALUE)).isFalse();
        assertThat(store.claim(BLOCKERS, 60L, DAY.plusDays(1), 1)).isTrue();

        assertThat(store.recipientsWithDispatch(BLOCKERS, DAY)).containsExactly(60L);
        assertThat(store.recipientsWithDispatch(BLOCKERS, DAY.minusDays(1))).isEmpty();
    }

    @Test
    void blockerNoticeDoesNotUseUpTheDailyCapOfOtherNotices() {
        assertThat(store.claim(BLOCKERS, 61L, DAY, 2)).isTrue();

        assertThat(store.claim("WORKER_DAY_START", 61L, DAY, 1)).isTrue();
        assertThat(store.claim("WORKER_SITE_INACTIVE", 61L, DAY, 1)).isFalse();
    }
}
