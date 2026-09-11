package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "otziv.security.session-revocation-dispatch-enabled", havingValue = "true")
public class UserSessionRevocationDispatcher {
    private final AuthSessionStateRepository state;
    private final UserSessionRevocationService revocations;
    private final UserSessionRevocationMetrics metrics;

    @Scheduled(fixedDelayString = "${otziv.security.session-revocation-delay-ms:10000}", initialDelay = 30000)
    public void tick() {
        List<AuthSessionStateRepository.Mutation> operations;
        try {
            state.classifyInterruptedPasswords();
            operations = state.dispatchable(20);
        }
        catch (RuntimeException failure) { metrics.error(); throw failure; }
        for (var operation : operations) {
            try {
                revocations.reconcile(operation.id());
                metrics.reconciled();
            }
            catch (RuntimeException failure) {
                metrics.error();
                state.defer(operation.id());
                log.warn("AUTH_SESSION_RECONCILIATION_PENDING operation={}", operation.id());
            }
        }
    }
}
