package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class UserSessionSecurityService {
    private final AuthSessionStateRepository state;
    private final KeycloakSessionAuthority authority;
    private final PlatformTransactionManager transactionManager;

    @Value("${otziv.security.session-revocation-mode:off}")
    private String mode = "off";

    @Value("${otziv.security.session-revocation-cutover-confirmed:false}")
    private boolean cutoverConfirmed;
    @Value("${otziv.security.session-revocation-dispatch-enabled:false}")
    private boolean dispatchEnabled;

    @Value("${otziv.security.issuer-generation-required:false}")
    private boolean issuerGenerationRequired;

    @jakarta.annotation.PostConstruct
    void validateMode() {
        if (!Set.of("off", "shadow", "enforce").contains(mode)) {
            throw new IllegalStateException("Invalid session-revocation-mode");
        }
        if ("enforce".equals(mode) && (!cutoverConfirmed || !dispatchEnabled || !issuerGenerationRequired)) {
            throw new IllegalStateException("Session enforcement requires confirmed fleet cutover, revocation dispatcher and issuer generation");
        }
    }

    public Decision verify(User user, Jwt jwt) {
        if ("off".equals(mode)) return new Decision(true, false, "off");
        try {
            String rejection = check(user, jwt);
            return new Decision(rejection == null || "shadow".equals(mode), false,
                    rejection == null ? "accepted" : rejection);
        } catch (RuntimeException failure) {
            // Unavailability is not a revoked session: 503 preserves stored mobile credentials.
            return new Decision("shadow".equals(mode), true, "authority_unavailable");
        }
    }

    private String check(User user, Jwt jwt) {
        Object rawSid = jwt.getClaims().get("sid");
        if (!(rawSid instanceof String sid) || !sid.matches("[A-Za-z0-9._:-]{1,255}")) return "session_id_missing_or_malformed";
        String subject = jwt.getSubject();
        if (subject == null || !subject.equals(user.getKeycloakId())) return "session_subject_mismatch";
        if (state.pending(user.getId())) return "security_mutation_pending";
        var existing = state.binding(subject, sid);
        if (existing.isPresent() && (existing.get().userId() != user.getId()
                || existing.get().epoch() != user.getAuthEpoch())) return "session_generation_revoked";
        Set<String> roles = user.getRoles() == null ? Set.of() : user.getRoles().stream()
                .filter(Objects::nonNull).map(Role::getName).collect(Collectors.toSet());
        var evidence = authority.verify(jwt, subject, sid, roles);
        if (!evidence.active()) return evidence.reason();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            var current = state.lockSnapshot(user.getId());
            if (current == null || !current.active() || !subject.equals(current.subject())
                    || current.epoch() != user.getAuthEpoch() || !state.roles(user.getId()).equals(roles)) {
                return "security_state_changed_during_authority_check";
            }
            if (state.pending(user.getId())) return "security_mutation_pending";
            var binding = state.binding(subject, sid);
            if (binding.isPresent()) {
                var known = binding.get();
                if (known.userId() != user.getId() || known.epoch() != current.epoch()
                        || known.credentialTime() != evidence.credentialTime()) return "session_generation_revoked";
            } else if (!"shadow".equals(mode)) {
                // Shadow never writes a potentially partial session generation.
                state.bind(user.getId(), subject, sid, current.epoch(), evidence);
            }
            return null;
        });
    }

    public record Decision(boolean allowed, boolean unavailable, String reason) {}
}
