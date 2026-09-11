package com.hunt.otziv.worker_activity.account_action;

import com.hunt.otziv.config.settings.service.AppSettingService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The short, independent transaction never keeps a card/order lock while waiting out the cooldown. */
@Repository
public class WorkerAccountActionCooldownRepository {
    private static final String NOW_SQL = "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    @Autowired
    public WorkerAccountActionCooldownRepository(WorkerAccountActionCooldownConnectionPool pool) {
        this(pool.jdbc(), pool.transactionManager());
    }

    WorkerAccountActionCooldownRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // State must observe an admission committed after the earlier settings/identity reads.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Admission admit(String username) {
        return Objects.requireNonNull(transaction.execute(status -> {
            Policy policy = policy();
            int durationSeconds = policy.durationSeconds();
            long userId = requireUserId(username);
            if (!policy.enabled() || durationSeconds == 0) {
                return new Admission(true, WorkerAccountActionCooldownState.of(false, durationSeconds, 0, databaseNow()));
            }
            // The primary key serializes simultaneous first uses as well as subsequent requests.
            jdbc.update("""
                    INSERT INTO worker_account_action_cooldowns (worker_user_id, available_at_epoch_millis)
                    VALUES (?, 0) ON DUPLICATE KEY UPDATE worker_user_id = worker_user_id
                    """, userId);
            long availableAt = Objects.requireNonNull(jdbc.queryForObject("""
                    SELECT available_at_epoch_millis FROM worker_account_action_cooldowns
                    WHERE worker_user_id = ? FOR UPDATE
                    """, Long.class, userId));
            // Read the common DB clock after acquiring the lock; application replicas may have clock skew.
            long now = databaseNow();
            boolean accepted = availableAt <= now;
            if (accepted) {
                availableAt = now + durationSeconds * 1000L;
                jdbc.update("""
                        UPDATE worker_account_action_cooldowns SET available_at_epoch_millis = ?
                        WHERE worker_user_id = ?
                        """, availableAt, userId);
            }
            return new Admission(accepted, WorkerAccountActionCooldownState.of(durationSeconds, availableAt, now));
        }));
    }

    public WorkerAccountActionCooldownState currentState(String username) {
        return Objects.requireNonNull(transaction.execute(status -> {
            Policy policy = policy();
            long userId = requireUserId(username);
            return jdbc.queryForObject("""
                    SELECT COALESCE((SELECT available_at_epoch_millis FROM worker_account_action_cooldowns
                                    WHERE worker_user_id = ?), 0),
                    """ + NOW_SQL, (rs, row) -> WorkerAccountActionCooldownState.of(
                    policy.enabled(), policy.durationSeconds(), rs.getLong(1), rs.getLong(2)), userId);
        }));
    }

    private long requireUserId(String username) {
        return jdbc.query("SELECT id FROM users WHERE username = ?", (rs, row) -> rs.getLong(1), username)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "Не удалось определить специалиста"));
    }

    private long databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT " + NOW_SQL, Long.class));
    }

    private Policy policy() {
        // One statement observes both values from the same committed settings snapshot.
        return jdbc.queryForObject("""
                SELECT (SELECT setting_value FROM app_settings WHERE setting_key = ?),
                       (SELECT setting_value FROM app_settings WHERE setting_key = ?)
                """, (rs, row) -> new Policy(parseEnabled(rs.getString(1)), parseDuration(rs.getString(2))),
                AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_ENABLED,
                AppSettingService.WORKER_ACCOUNT_ACTION_COOLDOWN_SECONDS);
    }

    public static boolean parseEnabled(String stored) {
        // Match AppSettingService's safety-switch parsing: absent or malformed values keep the enabled default.
        String value = stored == null ? "" : stored.trim();
        return !"false".equalsIgnoreCase(value) && !"0".equals(value) && !"no".equalsIgnoreCase(value);
    }

    static int parseDuration(String stored) {
        try {
            int duration = stored == null ? 60 : Integer.parseInt(stored.trim());
            return duration >= 0 && duration <= 3600 ? duration : 60;
        } catch (NumberFormatException ex) {
            return 60;
        }
    }

    public record Admission(boolean accepted, WorkerAccountActionCooldownState state) { }

    private record Policy(boolean enabled, int durationSeconds) { }
}
