package com.hunt.otziv.worker_activity.account_action;

import java.time.Instant;

public record WorkerAccountActionCooldownState(
        boolean enabled,
        int durationSeconds,
        int remainingSeconds,
        Instant availableAt,
        Instant serverNow
) {
    static WorkerAccountActionCooldownState of(int durationSeconds, long availableAtMillis, long nowMillis) {
        return of(true, durationSeconds, availableAtMillis, nowMillis);
    }

    static WorkerAccountActionCooldownState of(boolean configuredEnabled, int durationSeconds,
                                               long availableAtMillis, long nowMillis) {
        boolean enabled = configuredEnabled && durationSeconds > 0;
        long remainingMillis = enabled ? Math.max(0, availableAtMillis - nowMillis) : 0;
        return new WorkerAccountActionCooldownState(enabled, durationSeconds,
                (int) ((remainingMillis + 999) / 1000),
                remainingMillis > 0 ? Instant.ofEpochMilli(availableAtMillis) : null,
                Instant.ofEpochMilli(nowMillis));
    }
}
