package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient.CredentialView;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient.SessionView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakSessionAuthorityTest {
    KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    KeycloakSessionAuthority authority = new KeycloakSessionAuthority(keycloak);
    Jwt token = Jwt.withTokenValue("synthetic-token").header("alg", "RS256").subject("subject")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
            .claim("sid", "sid").claim("azp", "mobile").build();

    void introspection() {
        when(keycloak.introspectAccessToken("synthetic-token")).thenReturn(Map.of(
                "active", true, "sub", "subject", "sid", "sid", "client_id", "mobile"));
    }

    @Test void verifiesOnlineSessionAndCurrentPasswordFence() {
        introspection();
        when(keycloak.userSessions("subject")).thenReturn(List.of(session(2000)));
        when(keycloak.credentials("subject")).thenReturn(List.of(new CredentialView("pw", "password", 1001L)));
        when(keycloak.currentRealmRoleNames("subject")).thenReturn(Set.of("CLIENT"));
        var evidence = authority.verify(token, "subject", "sid", Set.of("ROLE_CLIENT"));
        assertThat(evidence.active()).isTrue();
        assertThat(evidence.offline()).isFalse();
    }

    @Test void offlineLookupUsesInternalUuidAndExactSid() {
        introspection();
        when(keycloak.userSessions("subject")).thenReturn(List.of());
        when(keycloak.offlineClientUuid("subject","mobile")).thenReturn(java.util.Optional.of("internal-uuid"));
        when(keycloak.offlineSessions("subject", "internal-uuid")).thenReturn(List.of(session(2000)));
        when(keycloak.credentials("subject")).thenReturn(List.of(new CredentialView("pw", "password", 1000L)));
        when(keycloak.currentRealmRoleNames("subject")).thenReturn(Set.of());
        assertThat(authority.verify(token, "subject", "sid", Set.of()).offline()).isTrue();
        verify(keycloak).offlineSessions("subject", "internal-uuid");
    }

    @Test void resetInSameSecondDoesNotRoundOldSessionIntoAcceptance() {
        introspection();
        when(keycloak.userSessions("subject")).thenReturn(List.of(session(1000)));
        when(keycloak.credentials("subject")).thenReturn(List.of(new CredentialView("pw", "password", 1001L)));
        assertThat(authority.verify(token, "subject", "sid", Set.of()).reason()).isEqualTo("issuer_credential_changed");
    }

    @Test void missingCredentialTimestampIsUnsupportedRatherThanZero() {
        introspection();
        when(keycloak.userSessions("subject")).thenReturn(List.of(session(2000)));
        when(keycloak.credentials("subject")).thenReturn(List.of(new CredentialView("pw", "password", null)));
        assertThat(authority.verify(token, "subject", "sid", Set.of()).reason())
                .isEqualTo("issuer_credential_fence_unsupported");
    }

    @Test void externalLogoutRejectsWithoutBindingEvidence() {
        when(keycloak.introspectAccessToken("synthetic-token")).thenReturn(Map.of("active", false));
        assertThat(authority.verify(token, "subject", "sid", Set.of()).active()).isFalse();
        verify(keycloak, never()).credentials(anyString());
    }

    @Test void roleRemovalOutsideBackendRejectsRatherThanImportingIssuerRoles() {
        introspection();
        when(keycloak.userSessions("subject")).thenReturn(List.of(session(2000)));
        when(keycloak.credentials("subject")).thenReturn(List.of(new CredentialView("pw", "password", 1000L)));
        when(keycloak.currentRealmRoleNames("subject")).thenReturn(Set.of("CLIENT"));
        assertThat(authority.verify(token, "subject", "sid", Set.of("ROLE_ADMIN")).reason())
                .isEqualTo("issuer_security_roles_changed");
    }

    @Test void introspectionSubjectMismatchNeverUsesAnotherUsersSession() {
        when(keycloak.introspectAccessToken("synthetic-token")).thenReturn(Map.of(
                "active", true, "sub", "different-subject", "sid", "sid", "client_id", "mobile"));
        assertThat(authority.verify(token, "subject", "sid", Set.of()).active()).isFalse();
        verify(keycloak, never()).userSessions(anyString());
    }

    SessionView session(long start) { return new SessionView("sid", "subject", start, Map.of("uuid", "mobile")); }
}
