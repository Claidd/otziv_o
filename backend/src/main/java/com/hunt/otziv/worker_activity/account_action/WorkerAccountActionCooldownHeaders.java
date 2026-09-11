package com.hunt.otziv.worker_activity.account_action;

import java.util.function.BiConsumer;

final class WorkerAccountActionCooldownHeaders {
    private WorkerAccountActionCooldownHeaders() { }

    static void write(WorkerAccountActionCooldownState state, BiConsumer<String, String> header) {
        header.accept("X-Worker-Account-Action-Enabled", String.valueOf(state.enabled()));
        header.accept("X-Worker-Account-Action-Duration-Seconds", String.valueOf(state.durationSeconds()));
        header.accept("X-Worker-Account-Action-Server-Now", state.serverNow().toString());
        header.accept("X-Worker-Account-Action-Available-At",
                state.availableAt() == null ? "" : state.availableAt().toString());
        header.accept("Cache-Control", "no-store");
    }
}
