package com.hunt.otziv.u_users.config;

import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalJwtSecurityStateFilterTest {

    @Mock private UserRepository userRepository;
    @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;
    @Mock private com.hunt.otziv.u_users.service.UserSessionSecurityService sessionSecurity;

    @org.junit.jupiter.api.BeforeEach
    void compatibleSessionMode() {
        org.mockito.Mockito.lenient().when(sessionSecurity.verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.hunt.otziv.u_users.service.UserSessionSecurityService.Decision(true, false, "off"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void inactiveLocalUserRevokesOtherwiseValidJwt() throws Exception {
        LocalJwtSecurityStateFilter filter = new LocalJwtSecurityStateFilter(userRepository, meterRegistryProvider, sessionSecurity);
        User user = user("alice", "sub-1", false, 2L, "ROLE_MANAGER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(token("alice", "sub-1", 2L, "ROLE_MANAGER"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void inactiveLocalUserFallsBackToAnonymousOnPublicCapabilityPage() throws Exception {
        LocalJwtSecurityStateFilter filter = new LocalJwtSecurityStateFilter(userRepository, meterRegistryProvider, sessionSecurity);
        User user = user("alice", "sub-1", false, 2L, "ROLE_MANAGER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(token("alice", "sub-1", 2L, "ROLE_MANAGER"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/review-check/order-1");
        request.setServletPath("/api/review-check/order-1");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void staleJwtRolesAreReplacedWithCanonicalLocalRoles() throws Exception {
        LocalJwtSecurityStateFilter filter = new LocalJwtSecurityStateFilter(userRepository, meterRegistryProvider, sessionSecurity);
        User user = user("alice", "sub-1", true, 4L, "ROLE_WORKER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(token("alice", "sub-1", 4L, "ROLE_ADMIN"));

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/me"),
                new MockHttpServletResponse(),
                new MockFilterChain()
        );

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("ROLE_WORKER")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void mismatchedAuthEpochRevokesJwt() throws Exception {
        LocalJwtSecurityStateFilter filter = new LocalJwtSecurityStateFilter(userRepository, meterRegistryProvider, sessionSecurity);
        User user = user("alice", "sub-1", true, 9L, "ROLE_MANAGER");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(token("alice", "sub-1", 8L, "ROLE_MANAGER"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/me"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void exactConfiguredServiceAccountBypassesHumanLocalStateLookup() throws Exception {
        LocalJwtSecurityStateFilter filter = new LocalJwtSecurityStateFilter(userRepository, meterRegistryProvider, sessionSecurity);
        ReflectionTestUtils.setField(
                filter,
                "localStateExemptClientIds",
                "otziv-smoke-ai-admin, otziv-smoke-ai-manager"
        );
        SecurityContextHolder.getContext().setAuthentication(serviceAccountToken("otziv-smoke-ai-admin"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/admin/payments/tbank-status"),
                new MockHttpServletResponse(),
                chain
        );

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void serviceAccountPrefixIsNotEnoughForExemption() throws Exception {
        LocalJwtSecurityStateFilter filter = new LocalJwtSecurityStateFilter(userRepository, meterRegistryProvider, sessionSecurity);
        ReflectionTestUtils.setField(filter, "localStateExemptClientIds", "otziv-smoke-ai-admin");
        when(userRepository.findByUsername("service-account-otziv-smoke-ai-admin-extra"))
                .thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                serviceAccountToken("otziv-smoke-ai-admin-extra")
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/admin"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private JwtAuthenticationToken token(String username, String subject, long authEpoch, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", username)
                .claim("auth_epoch", authEpoch)
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), username);
    }

    @Test
    void missingEpochKeepsCompatibilityButMalformedPresentClaimIsRejected() throws Exception {
        var filter = new LocalJwtSecurityStateFilter(userRepository,meterRegistryProvider,sessionSecurity);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice","sub-1",true,1,"ROLE_CLIENT")));
        Jwt missing = Jwt.withTokenValue("synthetic").header("alg","RS256").subject("sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(missing,List.of(),"alice"));
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET","/api/me"),new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isNotNull();
        Jwt malformed = Jwt.withTokenValue("synthetic").header("alg","RS256").subject("sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("auth_epoch",1.5).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(malformed,List.of(),"alice"));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/me"),response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void issuerOutageReturns503WithoutReportingRevokedSession() throws Exception {
        var filter = new LocalJwtSecurityStateFilter(userRepository,meterRegistryProvider,sessionSecurity);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice","sub-1",true,1,"ROLE_CLIENT")));
        when(sessionSecurity.verify(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.hunt.otziv.u_users.service.UserSessionSecurityService.Decision(false,true,"authority_unavailable"));
        SecurityContextHolder.getContext().setAuthentication(token("alice","sub-1",1,"ROLE_CLIENT"));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/me"),response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    private JwtAuthenticationToken serviceAccountToken(String clientId) {
        String username = "service-account-" + clientId;
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("service-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", username)
                .claim("azp", clientId)
                .build();
        return new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                username
        );
    }

    private User user(String username, String keycloakId, boolean active, long authEpoch, String roleName) {
        Role role = new Role();
        role.setName(roleName);
        return User.builder()
                .username(username)
                .keycloakId(keycloakId)
                .active(active)
                .authEpoch(authEpoch)
                .roles(new HashSet<>(List.of(role)))
                .build();
    }
}
