package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Live issuer evidence. No token, password or credential secret is persisted or logged. */
@Service
@RequiredArgsConstructor
public class KeycloakSessionAuthority {
    private final KeycloakAdminClient keycloak;
    private final Semaphore capacity = new Semaphore(8);

    @org.springframework.beans.factory.annotation.Value("${otziv.security.issuer-generation-required:false}")
    private boolean issuerGenerationRequired;

    public Evidence verify(Jwt jwt, String subject, String sid, Set<String> localRoles) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Issuer authority must be read outside the business transaction");
        }
        if (!capacity.tryAcquire()) throw new IllegalStateException("Issuer authority capacity exhausted");
        try {
            Map<String, Object> result = keycloak.introspectAccessToken(jwt.getTokenValue());
            if (result == null) throw new IllegalStateException("Issuer introspection unavailable");
            if (!Boolean.TRUE.equals(result.get("active"))) return Evidence.rejected("issuer_session_inactive");
            if (!Objects.equals(subject, result.get("sub")) || !Objects.equals(sid, result.get("sid"))) {
                return Evidence.rejected("issuer_session_identity_mismatch");
            }
            String clientId = jwt.getClaimAsString("azp");
            if (clientId == null || clientId.isBlank()
                    || !Objects.equals(clientId, result.get("client_id"))) {
                return Evidence.rejected("issuer_client_mismatch");
            }
            var session = keycloak.userSessions(subject).stream()
                    .filter(item -> sid.equals(item.id()) && subject.equals(item.userId())).findFirst();
            boolean offline = false;
            if (session.isEmpty()) {
                var clientUuid = keycloak.offlineClientUuid(subject, clientId);
                if (clientUuid.isEmpty()) return Evidence.rejected("issuer_offline_grant_missing");
                session = keycloak.offlineSessions(subject, clientUuid.get()).stream()
                        .filter(item -> sid.equals(item.id()) && subject.equals(item.userId())).findFirst();
                offline = true;
            }
            if (session.isEmpty() || session.get().start() <= 0 || session.get().clients() == null
                    || !session.get().clients().containsValue(clientId)) {
                return Evidence.rejected("issuer_session_missing");
            }
            var passwords = keycloak.credentials(subject).stream()
                    .filter(item -> "password".equals(item.type())).toList();
            if (passwords.size() != 1 || passwords.getFirst().createdDate() == null
                    || passwords.getFirst().createdDate() <= 0) {
                return Evidence.rejected("issuer_credential_fence_unsupported");
            }
            long credentialTime = passwords.getFirst().createdDate();
            // Keycloak session start has second precision; credential timestamps have milliseconds.
            // Ambiguous same-second logins are rejected, never rounded into accepting an old session.
            if (session.get().start() <= credentialTime) return Evidence.rejected("issuer_credential_changed");
            Set<String> issuerRoles = keycloak.currentRealmRoleNames(subject);
            if (!localRoles.stream().allMatch(role -> issuerRoles.contains(role)
                    || issuerRoles.contains(role.startsWith("ROLE_") ? role.substring(5) : role))) {
                return Evidence.rejected("issuer_security_roles_changed");
            }
            if (issuerGenerationRequired) {
                Object captured = jwt.getClaims().get("otziv_session_generation");
                if (!(captured instanceof String generation) || !generation.matches("v1:[0-9]{1,19}:[0-9]{1,19}"))
                    return Evidence.rejected("issuer_session_generation_missing_or_malformed");
                // The claim is an immutable authentication-session note. Refresh must
                // never copy the user's current generation into a previously created sid.
                if (!generation.equals(keycloak.currentSecurityGeneration(subject)))
                    return Evidence.rejected("issuer_session_generation_revoked");
            }
            return new Evidence(true, "active", credentialTime, session.get().start(), offline);
        } finally {
            capacity.release();
        }
    }

    public record Evidence(boolean active, String reason, long credentialTime, long sessionStart, boolean offline) {
        static Evidence rejected(String reason) { return new Evidence(false, reason, 0, 0, false); }
    }
}
