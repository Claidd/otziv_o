package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

class IssuerGenerationAuthorityTest {
    final KeycloakAdminClient issuer = mock(KeycloakAdminClient.class);
    final KeycloakSessionAuthority authority = new KeycloakSessionAuthority(issuer);

    @BeforeEach void liveUnchangedRolesAndPassword() {
        ReflectionTestUtils.setField(authority,"issuerGenerationRequired",true);
        when(issuer.introspectAccessToken("fixture")).thenReturn(Map.of("active",true,"sub","user","sid","session","client_id","mobile"));
        when(issuer.userSessions("user")).thenReturn(List.of(new KeycloakAdminClient.SessionView("session","user",2000L,Map.of("id","mobile"))));
        when(issuer.credentials("user")).thenReturn(List.of(new KeycloakAdminClient.CredentialView("password","password",1000L)));
        when(issuer.currentRealmRoleNames("user")).thenReturn(Set.of("CLIENT"));
    }

    Jwt token(Object generation) {
        var token = Jwt.withTokenValue("fixture").header("alg","RS256").subject("user").claim("sid","session").claim("azp","mobile");
        if (generation != null) token.claim("otziv_session_generation",generation);
        return token.build();
    }

    @Test void roleOrEnabledRestorationDoesNotReviveCapturedGeneration() {
        when(issuer.currentSecurityGeneration("user")).thenReturn("v1:3:5");
        assertThat(authority.verify(token("v1:3:3"),"user","session",Set.of("ROLE_CLIENT")).reason())
                .isEqualTo("issuer_session_generation_revoked");
        assertThat(authority.verify(token("v1:3:5"),"user","session",Set.of("ROLE_CLIENT")).active()).isTrue();
    }

    @Test void realmRoleMutationInvalidatesOtherwiseCurrentUserGeneration() {
        when(issuer.currentSecurityGeneration("user")).thenReturn("v1:4:3");
        assertThat(authority.verify(token("v1:3:3"),"user","session",Set.of("ROLE_CLIENT")).active()).isFalse();
    }

    @Test void legacyAndMalformedClaimsAreNeverBackfilledFromCurrentIssuerValue() {
        for (Object value : java.util.Arrays.asList(null,1,"v1:-1:0","v1:0:0:0","current"))
            assertThat(authority.verify(token(value),"user","session",Set.of("ROLE_CLIENT")).reason())
                    .isEqualTo("issuer_session_generation_missing_or_malformed");
        verify(issuer,never()).currentSecurityGeneration(anyString());
    }

    @Test void unavailableHistoryDoesNotBecomeAnAuthenticatedResponse() {
        when(issuer.currentSecurityGeneration("user")).thenThrow(new IllegalStateException("fixture unavailable"));
        assertThatThrownBy(() -> authority.verify(token("v1:0:0"),"user","session",Set.of("ROLE_CLIENT")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void strictStartupRequiresIssuerGateInAdditionToOldCutoverFlags() {
        var security = new UserSessionSecurityService(null,null,null);
        ReflectionTestUtils.setField(security,"mode","enforce");
        ReflectionTestUtils.setField(security,"cutoverConfirmed",true);
        ReflectionTestUtils.setField(security,"dispatchEnabled",true);
        assertThatThrownBy(security::validateMode).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(security,"issuerGenerationRequired",true);
        assertThatCode(security::validateMode).doesNotThrowAnyException();
    }
}
