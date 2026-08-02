package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserControllerTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void meAddsLocalSecurityStateForLinkedKeycloakUser() {
        User localUser = User.builder()
                .id(17L)
                .username("worker")
                .keycloakId("keycloak-17")
                .active(true)
                .authEpoch(4L)
                .build();
        when(userRepository.findByKeycloakId("keycloak-17")).thenReturn(Optional.of(localUser));

        Map<String, Object> response = new CurrentUserController(userRepository).me(jwtAuthentication(
                "keycloak-17",
                "worker"
        ));

        assertEquals(17L, response.get("localUserId"));
        assertEquals(true, response.get("active"));
        assertEquals(4L, response.get("authEpoch"));
        assertEquals("worker", response.get("name"));
    }

    @Test
    void meRemainsCompatibleForServicePrincipalWithoutLocalUser() {
        when(userRepository.findByKeycloakId("service-subject")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("service-account-worker")).thenReturn(Optional.empty());

        Map<String, Object> response = new CurrentUserController(userRepository).me(jwtAuthentication(
                "service-subject",
                "service-account-worker"
        ));

        assertEquals(true, response.get("authenticated"));
        assertNull(response.get("localUserId"));
        assertNull(response.get("active"));
        assertNull(response.get("authEpoch"));
    }

    private JwtAuthenticationToken jwtAuthentication(String subject, String username) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("preferred_username", username)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(), username);
    }
}
