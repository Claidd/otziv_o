package com.hunt.otziv.contractor_payments.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ContractorBonusTelegramNotificationMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383"
    ).withDatabaseName("bonus_group_contract").withUsername("root").withPassword("root");

    private JdbcTemplate jdbc;
    private ContractorBonusTelegramNotificationOutboxRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        repository = new ContractorBonusTelegramNotificationOutboxRepository(new NamedParameterJdbcTemplate(source));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        for (String table : new String[]{"contractor_bonus_telegram_notification_outbox",
                "contractor_accrual_adjustments", "contractor_payment_profiles", "managers", "users"}) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE users (id BIGINT PRIMARY KEY, fio VARCHAR(160), active BOOLEAN,
                    telegram_chat_id BIGINT, worker_telegram_group_chat_id BIGINT)
                """);
        jdbc.execute("CREATE TABLE managers (user_id BIGINT PRIMARY KEY, audit_telegram_group_chat_id BIGINT)");
        jdbc.execute("""
                CREATE TABLE contractor_payment_profiles
                    (id BIGINT PRIMARY KEY, user_id BIGINT, contractor_role VARCHAR(24))
                """);
        jdbc.execute("""
                CREATE TABLE contractor_accrual_adjustments (id BIGINT PRIMARY KEY,
                    profile_id BIGINT, kind VARCHAR(24), signed_amount_kopecks BIGINT,
                    effective_on DATE, reference_month DATE, reason VARCHAR(255), reversal_of_id BIGINT)
                """);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V1_10_331__contractor_bonus_telegram_notification_outbox.sql")).execute(source);
        jdbc.update("INSERT INTO users VALUES (32,'Виктория М.',true,987,-100987)");
        jdbc.update("INSERT INTO contractor_payment_profiles VALUES (1,32,'SPECIALIST')");
        bonus(1);
    }

    @Test
    void specialistUsesCurrentWorkGroupEvenWithoutPrivateChatAndRechecksReversal() {
        assertThat(repository.enqueue(1)).isTrue();
        jdbc.update("UPDATE users SET telegram_chat_id=NULL WHERE id=32");
        var first = repository.findBonus(1).orElseThrow();
        assertThat(first.employeeName()).isEqualTo("Виктория М.");
        assertThat(first.telegramGroupChatId()).isEqualTo(-100987L);
        assertThat(first.reversed()).isFalse();

        jdbc.update("UPDATE users SET worker_telegram_group_chat_id=-100999 WHERE id=32");
        jdbc.update("""
                INSERT INTO contractor_accrual_adjustments (id,kind,reversal_of_id) VALUES (2,'REVERSAL',1)
                """);
        var updated = repository.findBonus(1).orElseThrow();
        assertThat(updated.telegramGroupChatId()).isEqualTo(-100999L);
        assertThat(updated.reversed()).isTrue();
    }

    @Test
    void managerUsesItsOwnGroupAndNeverFallsBackToWorkerOrPrivateChat() {
        jdbc.update("UPDATE contractor_payment_profiles SET contractor_role='MANAGER' WHERE id=1");
        jdbc.update("INSERT INTO managers VALUES (32,-100555)");
        repository.enqueue(1);
        assertThat(repository.findBonus(1).orElseThrow().telegramGroupChatId()).isEqualTo(-100555L);
        jdbc.update("UPDATE managers SET audit_telegram_group_chat_id=NULL WHERE user_id=32");
        assertThat(repository.findBonus(1).orElseThrow().telegramGroupChatId()).isNull();
    }

    @Test
    void missingGroupCanBeLinkedBetweenAttemptsWithoutLosingTheIntent() {
        repository.enqueue(1);
        jdbc.update("UPDATE users SET worker_telegram_group_chat_id=NULL WHERE id=32");
        assertThat(repository.findBonus(1).orElseThrow().telegramGroupChatId()).isNull();
        var first = repository.tryAcquire(1,"first","test",Duration.ofMinutes(2)).orElseThrow();
        assertThat(repository.markFailed(first,"employee_work_telegram_group_missing",Duration.ZERO)).isTrue();
        jdbc.update("UPDATE users SET worker_telegram_group_chat_id=-100777 WHERE id=32");
        var retry = repository.tryAcquire(1,"second","test",Duration.ofMinutes(2)).orElseThrow();
        assertThat(retry.attemptCount()).isEqualTo(2);
        assertThat(repository.findBonus(1).orElseThrow().telegramGroupChatId()).isEqualTo(-100777L);
        assertThat(repository.markSent(first)).isFalse();
        assertThat(repository.markSent(retry)).isTrue();
        assertThat(repository.findDueAdjustmentIds(50)).isEmpty();
    }

    @Test
    void rolledBackBonusDoesNotLeaveANotificationToSend() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            bonus(2);
            repository.enqueue(2);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contractor_accrual_adjustments WHERE id=2",Long.class)).isZero();
        assertThat(repository.findDueAdjustmentIds(50)).isEmpty();
    }

    private void bonus(long id) {
        jdbc.update("""
                INSERT INTO contractor_accrual_adjustments
                    (id,profile_id,kind,signed_amount_kopecks,effective_on,reference_month,reason)
                VALUES (?,1,'BONUS',300000,'2026-11-01','2026-11-01','Лидерство в рейтинге')
                """,id);
    }
}
