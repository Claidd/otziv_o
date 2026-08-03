package com.hunt.otziv.u_users.config;

import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes local account state authoritative after offline JWT verification.
 * Deactivation and role removal therefore take effect without waiting for the
 * Keycloak access token to expire. If an auth-epoch mapper is enabled in
 * Keycloak, its claim additionally revokes password-change-era tokens.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocalJwtSecurityStateFilter extends OncePerRequestFilter {

    private static final String AUTH_EPOCH_CLAIM = "auth_epoch";

    private final UserRepository userRepository;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Value("${otziv.security.auth-epoch-claim-required:false}")
    private boolean authEpochClaimRequired;

    @Value("${otziv.security.local-state-exempt-client-ids:}")
    private String localStateExemptClientIds;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isExplicitlyExemptServiceAccount(token)) {
            increment("otziv.security.jwt.local_state_exempt", "service_account");
            filterChain.doFilter(request, response);
            return;
        }

        User user = userRepository.findByUsername(token.getName()).orElse(null);
        if (user == null || !user.isActive()) {
            rejectOrContinueAnonymously(
                    request,
                    response,
                    filterChain,
                    user == null ? "local_user_missing" : "local_user_inactive"
            );
            return;
        }
        if (hasText(user.getKeycloakId()) && !Objects.equals(user.getKeycloakId(), token.getToken().getSubject())) {
            rejectOrContinueAnonymously(request, response, filterChain, "keycloak_subject_mismatch");
            return;
        }

        Long tokenEpoch = authEpoch(token);
        if (tokenEpoch == null && authEpochClaimRequired) {
            rejectOrContinueAnonymously(request, response, filterChain, "auth_epoch_missing");
            return;
        }
        if (tokenEpoch != null && tokenEpoch.longValue() != user.getAuthEpoch()) {
            rejectOrContinueAnonymously(request, response, filterChain, "auth_epoch_mismatch");
            return;
        }
        if (tokenEpoch == null) {
            increment("otziv.security.jwt.auth_epoch_missing", "accepted");
        }

        Set<GrantedAuthority> canonicalAuthorities = new LinkedHashSet<>();
        token.getAuthorities().stream()
                .filter(authority -> authority != null && !authority.getAuthority().startsWith("ROLE_"))
                .forEach(canonicalAuthorities::add);
        if (user.getRoles() != null) {
            user.getRoles().stream()
                    .filter(Objects::nonNull)
                    .map(Role::getName)
                    .filter(LocalJwtSecurityStateFilter::hasText)
                    .map(String::trim)
                    .map(name -> name.startsWith("ROLE_") ? name : "ROLE_" + name)
                    .map(SimpleGrantedAuthority::new)
                    .forEach(canonicalAuthorities::add);
        }

        JwtAuthenticationToken canonical = new JwtAuthenticationToken(
                token.getToken(),
                canonicalAuthorities,
                token.getName()
        );
        canonical.setDetails(token.getDetails());
        SecurityContextHolder.getContext().setAuthentication(canonical);
        filterChain.doFilter(request, response);
    }

    private boolean isExplicitlyExemptServiceAccount(JwtAuthenticationToken token) {
        if (!hasText(localStateExemptClientIds)) {
            return false;
        }
        String clientId = claimText(token, "azp");
        if (!hasText(clientId)) {
            clientId = claimText(token, "client_id");
        }
        String username = claimText(token, "preferred_username");
        if (!hasText(clientId) || !Objects.equals(username, "service-account-" + clientId)) {
            return false;
        }
        String expectedClientId = clientId;
        return Arrays.stream(localStateExemptClientIds.split(",", -1))
                .map(String::trim)
                .filter(LocalJwtSecurityStateFilter::hasText)
                .anyMatch(expectedClientId::equals);
    }

    private String claimText(JwtAuthenticationToken token, String claimName) {
        Object value = token.getToken().getClaims().get(claimName);
        return value instanceof String text ? text.trim() : null;
    }

    private Long authEpoch(JwtAuthenticationToken token) {
        Object value = token.getToken().getClaims().get(AUTH_EPOCH_CLAIM);
        if (value == null) {
            value = token.getToken().getClaims().get("authEpoch");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        increment("otziv.security.jwt.rejected", reason);
        log.warn("JWT_LOCAL_SECURITY_STATE_REJECTED reason={}", reason);
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"Сессия отозвана. Войдите в систему заново.\"}");
    }

    private void rejectOrContinueAnonymously(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            String reason
    ) throws IOException, ServletException {
        if (isPublicCapabilityPath(applicationPath(request))) {
            increment("otziv.security.jwt.optional_rejected", reason);
            log.debug("JWT_LOCAL_SECURITY_STATE_DROPPED_ON_PUBLIC_PATH reason={} path={}", reason, applicationPath(request));
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }
        reject(response, reason);
    }

    private static boolean isPublicCapabilityPath(String path) {
        if (path == null) {
            return false;
        }
        int matrixSeparator = path.indexOf(';');
        String normalized = matrixSeparator >= 0 ? path.substring(0, matrixSeparator) : path;
        return matchesPathOrDescendant(normalized, "/api/payments/public")
                || matchesPathOrDescendant(normalized, "/api/review-check")
                || matchesPathOrDescendant(normalized, "/api/review-capability");
    }

    private static boolean matchesPathOrDescendant(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)
                ? path.substring(contextPath.length())
                : path;
    }

    private void increment(String metric, String outcome) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(metric, "outcome", outcome).increment();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
