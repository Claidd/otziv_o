package com.hunt.otziv.worker_activity.account_action;

import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Also handles the session-authenticated legacy card endpoints. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkerAccountActionCooldownExceptionHandler {
    @ExceptionHandler(WorkerAccountActionCooldownException.class)
    public ResponseEntity<CooldownError> handle(WorkerAccountActionCooldownException exception) {
        WorkerAccountActionCooldownState state = exception.state();
        HttpHeaders headers = new HttpHeaders();
        WorkerAccountActionCooldownHeaders.write(state, headers::set);
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(state.remainingSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(new CooldownError(
                exception.getReason(), WorkerAccountActionCooldownException.CODE, state.enabled(),
                state.durationSeconds(), state.remainingSeconds(), state.availableAt(), state.serverNow()));
    }

    public record CooldownError(String message, String code, boolean enabled, int durationSeconds,
                                int remainingSeconds, Instant availableAt, Instant serverNow) { }
}
