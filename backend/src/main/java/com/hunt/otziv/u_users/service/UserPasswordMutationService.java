package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.dto.ChangeKeycloakPasswordRequest;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserPasswordMutationService {
    private final UserRepository users;
    private final EntityManager entityManager;
    private final UserAuthEpochService epochs;
    private final AuthSessionStateRepository state;
    private final KeycloakAdminClient keycloak;
    private final UserSessionRevocationService revocations;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void change(long userId, ChangeKeycloakPasswordRequest request, Consumer<User> authorize) {
        var operation = revocations.inTransaction(() -> {
            User user = users.lockById(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Local user not found"));
            // The security filter may have loaded this entity in OSIV before a concurrent mutation.
            entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
            authorize.accept(user);
            if (user.getKeycloakId() == null || user.getKeycloakId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not linked to Keycloak");
            }
            if (state.pending(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A security mutation is awaiting reconciliation");
            }
            epochs.passwordChanged(user);
            users.flush();
            var pending = state.generation(userId, user.getAuthEpoch()).orElseThrow();
            if (!state.phase(pending.id(), "CAPTURE_REQUIRED", "PASSWORD_REQUESTED")) throw new IllegalStateException();
            return pending;
        });
        // The barrier is committed before the remote call. Never persist or automatically replay the password.
        try {
            keycloak.resetPassword(operation.subject(), request.getPassword(), request.isTemporary());
        } catch (RuntimeException failure) {
            revocations.inTransaction(() -> {
                state.lockSnapshot(userId);
                state.phase(operation.id(), "PASSWORD_REQUESTED", "PASSWORD_UNKNOWN");
                return null;
            });
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Password outcome requires reconciliation; operation=" + operation.id());
        }
        revocations.inTransaction(() -> {
            state.lockSnapshot(userId);
            if (!state.phase(operation.id(), "PASSWORD_REQUESTED", "CAPTURE_REQUIRED")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Security mutation phase changed");
            }
            return null;
        });
        revocations.reconcile(operation.id());
        if (!"COMPLETE".equals(state.mutation(operation.id()).orElseThrow().phase())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Session revocation is awaiting reconciliation");
        }
    }
}
