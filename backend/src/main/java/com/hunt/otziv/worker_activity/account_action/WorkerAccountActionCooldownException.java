package com.hunt.otziv.worker_activity.account_action;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class WorkerAccountActionCooldownException extends ResponseStatusException {
    public static final String CODE = "WORKER_ACCOUNT_ACTION_COOLDOWN";
    private final WorkerAccountActionCooldownState state;

    public WorkerAccountActionCooldownException(WorkerAccountActionCooldownState state) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Смена и блокировка аккаунтов доступны через "
                + state.remainingSeconds() + " сек.");
        this.state = state;
    }

    public WorkerAccountActionCooldownState state() {
        return state;
    }
}
