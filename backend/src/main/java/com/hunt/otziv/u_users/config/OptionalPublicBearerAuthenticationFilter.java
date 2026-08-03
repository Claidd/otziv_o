package com.hunt.otziv.u_users.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a valid bearer token on public capability pages, while keeping
 * those pages anonymous when a browser sends no token or an expired token.
 */
@RequiredArgsConstructor
@Slf4j
final class OptionalPublicBearerAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, AbstractAuthenticationToken> authenticationConverter;
    private final DefaultBearerTokenResolver bearerTokenResolver = new DefaultBearerTokenResolver();
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isPublicCapabilityPath(applicationPath(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || !existing.isAuthenticated()) {
            authenticateIfValid(request);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticateIfValid(HttpServletRequest request) {
        try {
            String tokenValue = bearerTokenResolver.resolve(request);
            if (tokenValue == null || tokenValue.isBlank()) {
                return;
            }
            Jwt jwt = jwtDecoder.decode(tokenValue);
            if (jwt == null) {
                return;
            }
            AbstractAuthenticationToken authentication = authenticationConverter.convert(jwt);
            if (authentication == null) {
                return;
            }
            authentication.setDetails(detailsSource.buildDetails(request));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (JwtException | OAuth2AuthenticationException | IllegalArgumentException exception) {
            // Public capability URLs remain usable when a browser carries a stale token.
            log.debug("Optional bearer token was ignored for public capability path {}", applicationPath(request));
        }
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)
                ? path.substring(contextPath.length())
                : path;
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
}
