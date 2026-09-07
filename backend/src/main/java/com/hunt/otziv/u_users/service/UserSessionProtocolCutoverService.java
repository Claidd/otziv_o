package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Explicit per-user bootstrap; never an automatic mass logout during deployment. */
@Service
@RequiredArgsConstructor
public class UserSessionProtocolCutoverService {
    private final UserRepository users;
    private final EntityManager entityManager;
    private final UserAuthEpochService epochs;
    private final AuthSessionStateRepository state;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String bootstrap(long userId) {
        var user = users.lockById(userId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.getKeycloakId() == null || user.getKeycloakId().isBlank()) throw new ResponseStatusException(BAD_REQUEST);
        var existing = state.cutover(userId);
        if (existing.isPresent() && existing.get().subject().equals(user.getKeycloakId())) return existing.get().id();
        if (state.pending(userId)) throw new ResponseStatusException(CONFLICT,"Resolve the existing mutation first");
        epochs.sessionProtocolCutover(user);
        users.flush();
        return state.generation(userId,user.getAuthEpoch()).orElseThrow().id();
    }
}
