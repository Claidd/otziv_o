package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository.Mutation;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository.Target;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Domain reconciliation; only immutable exact-session deletes are automatically retried. */
@Service
@RequiredArgsConstructor
public class UserSessionRevocationService {
    private final AuthSessionStateRepository state;
    private final KeycloakAdminClient keycloak;
    private final PlatformTransactionManager transactionManager;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcile(String operationId) {
        Mutation operation = state.mutation(operationId).orElseThrow();
        if ("CAPTURE_REQUIRED".equals(operation.phase())) {
            Set<Target> snapshot = capture(operation.subject());
            inTransaction(() -> {
                state.lockSnapshot(operation.userId());
                if (state.phase(operation.id(), "CAPTURE_REQUIRED", "DELETE_REQUIRED")) {
                    snapshot.forEach(target -> state.addTarget(operation.id(), target));
                }
                return null;
            });
        }
        Mutation current = state.mutation(operationId).orElseThrow();
        if (!"DELETE_REQUIRED".equals(current.phase())) return;
        List<Target> targets = state.targets(operationId);
        for (Target target : targets) keycloak.deleteSession(target.sid(), target.offline());
        Set<Target> remaining = capture(operation.subject());
        if (targets.stream().anyMatch(remaining::contains)) {
            throw new IllegalStateException("Issuer session deletion has not been confirmed");
        }
        inTransaction(() -> {
            state.lockSnapshot(operation.userId());
            state.phase(operationId, "DELETE_REQUIRED", "COMPLETE");
            return null;
        });
    }

    private Set<Target> capture(String subject) {
        Set<Target> result = new LinkedHashSet<>();
        for (var session : keycloak.userSessions(subject)) {
            if (!subject.equals(session.userId()) || session.id() == null) {
                throw new IllegalStateException("Issuer returned an incorrectly scoped user session");
            }
            result.add(new Target(session.id(), false));
        }
        for (String client : keycloak.offlineClientUuids(subject)) {
            for (var session : keycloak.offlineSessions(subject, client)) {
                if (!subject.equals(session.userId()) || session.id() == null) {
                    throw new IllegalStateException("Issuer returned an incorrectly scoped offline session");
                }
                result.add(new Target(session.id(), true));
            }
        }
        return result;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void resolveUnknownPassword(String operationId, String actor, String evidence) {
        if (actor == null || actor.isBlank() || actor.length() > 255 || evidence == null
                || evidence.isBlank() || evidence.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Actor and verified external outcome evidence are required");
        }
        inTransaction(() -> {
            Mutation operation = state.mutation(operationId).orElseThrow();
            state.lockSnapshot(operation.userId());
            if (!state.phase(operationId, "PASSWORD_UNKNOWN", "CAPTURE_REQUIRED")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Password mutation is not awaiting external reconciliation");
            }
            state.resolution(operationId, actor, evidence);
            return null;
        });
        reconcile(operationId);
    }

    <T> T inTransaction(Supplier<T> action) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> action.get());
    }
}
