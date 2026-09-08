package com.hunt.otziv.worker_activity.account_action;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class WorkerAccountActionCooldownMySqlIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
            .withDatabaseName("worker_account_action_cooldown").withUsername("root").withPassword("root");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private WorkerAccountActionCooldownRepository firstReplica;
    private WorkerAccountActionCooldownRepository secondReplica;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS worker_account_action_cooldowns");
        jdbc.execute("DROP TABLE IF EXISTS app_settings");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, username VARCHAR(255) NOT NULL UNIQUE) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE app_settings (setting_key VARCHAR(255) PRIMARY KEY, setting_value VARCHAR(255), updated_at DATETIME(6))");
        jdbc.update("INSERT INTO users (id, username) VALUES (1, 'first'), (2, 'second')");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1_10_286__worker_account_action_cooldown.sql"),
                new ClassPathResource("db/migration/V1_10_287__worker_account_action_cooldown_enabled.sql"))
                .execute(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        firstReplica = new WorkerAccountActionCooldownRepository(jdbc, transactions);
        secondReplica = new WorkerAccountActionCooldownRepository(new JdbcTemplate(dataSource), transactions);
    }

    @Test
    void explicitWorkersUseDatabaseAdmissionDespiteAmbientAdminAndRequestCacheCannotCrossActors() {
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("ambient-admin", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var service = new WorkerAccountActionCooldownService(firstReplica);
        var first = new UsernamePasswordAuthenticationToken("first", "unused", List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        var second = new UsernamePasswordAuthenticationToken("second", "unused", List.of(new SimpleGrantedAuthority("ROLE_WORKER")));
        try {
            // Charge B on another replica first, so inheriting A's request marker would be a bypass.
            var secondDeadline = secondReplica.admit("second").state().availableAt();
            service.admitAction(first);
            var firstDeadline = secondReplica.currentState("first").availableAt();
            assertThat(firstDeadline).isNotNull();
            service.admitAction(first); // Same actor's fallback must not be charged again.
            assertThat(secondReplica.currentState("first").availableAt()).isEqualTo(firstDeadline);
            assertThatThrownBy(() -> service.admitAction(second)).isInstanceOf(WorkerAccountActionCooldownException.class);
            assertThat(request.getAttribute(WorkerAccountActionCooldownService.ACCEPTED_REQUEST_ATTRIBUTE)).isNull();
            assertThat(secondReplica.currentState("second").availableAt()).isEqualTo(secondDeadline);

            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> service.admitAction(first)).isInstanceOf(WorkerAccountActionCooldownException.class);
            assertThat(secondReplica.currentState("first").availableAt()).isEqualTo(firstDeadline);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_account_action_cooldowns", Integer.class)).isEqualTo(2);
        } finally {
            RequestContextHolder.resetRequestAttributes();
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void simultaneousFirstUseAcrossReplicasAdmitsExactlyOneAndDoesNotExtendPause() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<WorkerAccountActionCooldownRepository.Admission>> futures = List.of(
                    executor.submit(() -> concurrentAdmission(firstReplica, ready, start)),
                    executor.submit(() -> concurrentAdmission(secondReplica, ready, start)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = futures.get(0).get(10, TimeUnit.SECONDS);
            var second = futures.get(1).get(10, TimeUnit.SECONDS);
            assertThat(List.of(first, second).stream().filter(WorkerAccountActionCooldownRepository.Admission::accepted).count())
                    .isEqualTo(1);
            assertThat(first.state().availableAt()).isEqualTo(second.state().availableAt());
            jdbc.update("UPDATE app_settings SET setting_value = '180' WHERE setting_key = 'worker.account-action.cooldown-seconds'");
            assertThat(secondReplica.admit("first").state().availableAt()).isEqualTo(first.state().availableAt());
            assertThat(firstReplica.admit("second").accepted()).isTrue();
        }
    }

    @Test
    void acceptedAttemptSurvivesBusinessRollbackAndDeadlineSurvivesNewRepository() {
        TransactionTemplate businessTransaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> businessTransaction.execute(status -> {
            assertThat(firstReplica.admit("first").accepted()).isTrue();
            throw new IllegalStateException("No replacement account");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(secondReplica.admit("first").accepted()).isFalse();
        assertThat(secondReplica.currentState("first").remainingSeconds()).isBetween(1, 60);
    }

    @Test
    void expiredPauseCanBeReplacedAndZeroSettingHidesActivePause() {
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key = 'worker.account-action.cooldown-seconds'", String.class))
                .isEqualTo("60");
        assertThat(firstReplica.admit("first").accepted()).isTrue();
        jdbc.update("UPDATE app_settings SET setting_value = '0' WHERE setting_key = 'worker.account-action.cooldown-seconds'");
        assertThat(firstReplica.currentState("first").enabled()).isFalse();
        assertThat(firstReplica.currentState("first").availableAt()).isNull();
        assertThat(secondReplica.admit("first").accepted()).isTrue();
        jdbc.update("UPDATE app_settings SET setting_value = '180' WHERE setting_key = 'worker.account-action.cooldown-seconds'");
        jdbc.update("UPDATE worker_account_action_cooldowns SET available_at_epoch_millis = 0 WHERE worker_user_id = 1");
        assertThat(secondReplica.admit("first").state().remainingSeconds()).isEqualTo(180);
    }

    @Test
    void disablingPreservesDurationAndLedgerAndReenablingEnforcesOriginalDeadline() {
        assertThat(jdbc.queryForObject("SELECT setting_value FROM app_settings WHERE setting_key = 'worker.account-action.cooldown-enabled'", String.class))
                .isEqualTo("true");
        jdbc.update("UPDATE app_settings SET setting_value = '180' WHERE setting_key = 'worker.account-action.cooldown-seconds'");
        var original = firstReplica.admit("first");
        assertThat(original.accepted()).isTrue();
        jdbc.update("UPDATE app_settings SET setting_value = 'false' WHERE setting_key = 'worker.account-action.cooldown-enabled'");

        assertDisabledWithDuration(firstReplica.currentState("first"), 180);
        for (int attempt = 0; attempt < 2; attempt++) {
            var disabledAdmission = secondReplica.admit("first");
            assertThat(disabledAdmission.accepted()).isTrue();
            assertDisabledWithDuration(disabledAdmission.state(), 180);
        }
        assertThat(jdbc.queryForObject("SELECT available_at_epoch_millis FROM worker_account_action_cooldowns WHERE worker_user_id = 1", Long.class))
                .isEqualTo(original.state().availableAt().toEpochMilli());
        assertThat(secondReplica.admit("second").accepted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM worker_account_action_cooldowns WHERE worker_user_id = 2", Integer.class))
                .isZero();

        jdbc.update("UPDATE app_settings SET setting_value = 'true' WHERE setting_key = 'worker.account-action.cooldown-enabled'");
        var restored = firstReplica.currentState("first");
        assertThat(restored.enabled()).isTrue();
        assertThat(restored.durationSeconds()).isEqualTo(180);
        assertThat(restored.availableAt()).isEqualTo(original.state().availableAt());
        var rejected = secondReplica.admit("first");
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.state().availableAt()).isEqualTo(original.state().availableAt());
    }

    @Test
    void missingAndMalformedEnabledSettingKeepLegacyPolicyActive() {
        jdbc.update("UPDATE app_settings SET setting_value = '180' WHERE setting_key = 'worker.account-action.cooldown-seconds'");
        jdbc.update("DELETE FROM app_settings WHERE setting_key = 'worker.account-action.cooldown-enabled'");
        var original = firstReplica.admit("first");
        assertThat(original.accepted()).isTrue();
        assertThat(original.state().enabled()).isTrue();
        assertThat(original.state().durationSeconds()).isEqualTo(180);
        assertThat(secondReplica.currentState("first").availableAt()).isEqualTo(original.state().availableAt());
        assertThat(secondReplica.admit("first").accepted()).isFalse();

        jdbc.update("INSERT INTO app_settings (setting_key, setting_value) VALUES ('worker.account-action.cooldown-enabled', 'invalid')");
        assertThat(secondReplica.currentState("first").enabled()).isTrue();
        var rejected = secondReplica.admit("first");
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.state().durationSeconds()).isEqualTo(180);
        assertThat(rejected.state().availableAt()).isEqualTo(original.state().availableAt());
    }

    private void assertDisabledWithDuration(WorkerAccountActionCooldownState state, int durationSeconds) {
        assertThat(state.enabled()).isFalse();
        assertThat(state.durationSeconds()).isEqualTo(durationSeconds);
        assertThat(state.remainingSeconds()).isZero();
        assertThat(state.availableAt()).isNull();
    }

    private WorkerAccountActionCooldownRepository.Admission concurrentAdmission(
            WorkerAccountActionCooldownRepository repository, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent request did not start");
        }
        return repository.admit("first");
    }
}
