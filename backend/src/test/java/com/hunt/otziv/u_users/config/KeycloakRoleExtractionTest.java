package com.hunt.otziv.u_users.config;

import com.hunt.otziv.config.jwt.service.JwtAuthFilter;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewRestrictionFilter;
import com.hunt.otziv.u_users.services.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KeycloakRoleExtractionTest {

    @Test
    void trustsOnlyAllowlistedRealmFlatAndBackendClientRoles() {
        SecurityConfig config = new SecurityConfig(
                mock(UserServiceImpl.class),
                new BCryptPasswordEncoder(),
                new RequestValidationFilter(),
                mock(JwtAuthFilter.class),
                mock(ManagerReportReviewRestrictionFilter.class)
        );
        ReflectionTestUtils.setField(config, "keycloakBackendClientId", "otziv-backend");
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("WORKER", "offline_access"))
                .claim("realm_access", Map.of("roles", List.of("MANAGER", "default-roles-realm")))
                .claim("resource_access", Map.of(
                        "otziv-backend", Map.of("roles", List.of("ADMIN", "uma_authorization")),
                        "account", Map.of("roles", List.of("OWNER"))
                ))
                .build();

        Set<String> roles = ReflectionTestUtils.invokeMethod(config, "extractKeycloakRoles", jwt);

        assertThat(roles).containsExactlyInAnyOrder("WORKER", "MANAGER", "ADMIN");
    }

    @Test
    void bearerResolverExemptionUsesEndpointBoundariesAndAcceptsMatrixParameters() {
        SecurityConfig config = new SecurityConfig(
                mock(UserServiceImpl.class),
                new BCryptPasswordEncoder(),
                new RequestValidationFilter(),
                mock(JwtAuthFilter.class),
                mock(ManagerReportReviewRestrictionFilter.class)
        );

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                config,
                "isBearerOptionalPublicPath",
                "/api/leads/import;source=vps"
        )).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                config,
                "isBearerOptionalPublicPath",
                "/api/payments/publicevil"
        )).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(
                config,
                "isBearerOptionalPublicPath",
                "/api/review-check/00000000-0000-0000-0000-000000000001"
        )).isTrue();
    }
}
