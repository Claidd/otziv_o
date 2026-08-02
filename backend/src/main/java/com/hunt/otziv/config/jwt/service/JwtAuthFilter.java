package com.hunt.otziv.config.jwt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final int MAX_INTEGRATION_BODY_BYTES = 1_048_576;
    private static final Map<String, IntegrationRule> RULES = Map.of(
            "/api/leads/import", new IntegrationRule("POST", JwtService.LEAD_TRANSFER_SUBJECT,
                    JwtService.IMPORT_SCOPE, LeadDtoTransfer.class),
            "/api/leads/modified", new IntegrationRule("GET", JwtService.LEAD_SYNC_SUBJECT,
                    "GET:/api/leads/modified", null),
            "/api/leads/sync", new IntegrationRule("POST", JwtService.LEAD_SYNC_SUBJECT,
                    "POST:/api/leads/sync", LeadDtoTransfer.class),
            "/api/leads/update", new IntegrationRule("POST", JwtService.LEAD_SYNC_SUBJECT,
                    "POST:/api/leads/update", LeadUpdateDto.class),
            "/api/dispatch-settings/cron", new IntegrationRule("GET", JwtService.LEAD_SYNC_SUBJECT,
                    "GET:/api/dispatch-settings/cron", null)
    );

    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final LeadTokenReplayGuard replayGuard;

    @Value("${lead.integration.legacy-bearer-enabled:true}")
    private boolean legacyBearerEnabled = true;

    @Value("${lead.integration.legacy-bearer-accept-until:2026-08-17T00:00:00Z}")
    private String legacyBearerAcceptUntil = "2026-08-17T00:00:00Z";

    @Autowired
    public JwtAuthFilter(ObjectMapper objectMapper, JwtService jwtService, LeadTokenReplayGuard replayGuard) {
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.replayGuard = replayGuard;
    }

    /** Compatibility constructor for focused security contract tests. */
    public JwtAuthFilter(ObjectMapper objectMapper, JwtService jwtService) {
        this(objectMapper, jwtService, new LeadTokenReplayGuard());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String applicationPath = applicationPath(request);
        IntegrationRule rule = RULES.get(applicationPath);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!rule.method().equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method is not allowed");
            return;
        }

        HttpServletRequest effectiveRequest = request;
        String replayId;
        Claims claims;
        try {
            SelectedToken selectedToken = extractToken(request);
            claims = selectedToken.modern()
                    ? jwtService.parseAndValidate(selectedToken.value(), rule.subject(), rule.scope())
                    : jwtService.parseLegacyAndValidate(selectedToken.value(), rule.subject());
            if (rule.payloadType() != null) {
                CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                        request,
                        MAX_INTEGRATION_BODY_BYTES
                );
                effectiveRequest = wrapped;
                Object payload = objectMapper.readValue(wrapped.getCachedBody(), rule.payloadType());
                if (selectedToken.modern() || JwtService.LEAD_TRANSFER_SUBJECT.equals(rule.subject())) {
                    String expected = claims.get("checksum", String.class);
                    String actual = selectedToken.modern()
                            ? jwtService.generateChecksum(payload)
                            : legacyChecksum(payload);
                    if (expected == null || !constantTimeEquals(expected, actual)) {
                        throw new AuthException(HttpServletResponse.SC_FORBIDDEN, "Payload signature mismatch");
                    }
                }
            }
            replayId = selectedToken.modern()
                    ? claims.getId()
                    : "legacy:" + jwtService.tokenFingerprint(selectedToken.value());
            if (!replayGuard.consume(replayId)) {
                throw new AuthException(HttpServletResponse.SC_FORBIDDEN, "Integration token was already used");
            }
        } catch (BodyTooLargeException exception) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, exception.getMessage());
            return;
        } catch (AuthException exception) {
            log.warn("Integration authorization rejected for {}: {}", request.getRequestURI(), exception.getMessage());
            response.sendError(exception.status(), exception.getMessage());
            return;
        } catch (JwtException | IllegalArgumentException exception) {
            log.warn("Invalid integration token for {}", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid integration token");
            return;
        } catch (IOException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid integration request body");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims.getSubject(), null, List.of())
        );
        try {
            filterChain.doFilter(effectiveRequest, response);
        } catch (RuntimeException | Error | ServletException | IOException exception) {
            replayGuard.release(replayId);
            throw exception;
        }
        if (response.getStatus() >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
            replayGuard.release(replayId);
        }
    }

    private String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        int matrixSeparator = path.indexOf(';');
        return matrixSeparator >= 0 ? path.substring(0, matrixSeparator) : path;
    }

    private SelectedToken extractToken(HttpServletRequest request) {
        String token = request.getHeader(LeadIntegrationHeaders.TOKEN);
        if (token != null && !token.isBlank()) {
            return new SelectedToken(token.trim(), true);
        }
        String authorization = request.getHeader("Authorization");
        if (legacyBearerEnabled
                && legacyBearerWindowIsOpen()
                && authorization != null
                && authorization.startsWith("Bearer ")
                && !authorization.substring(7).isBlank()) {
            return new SelectedToken(authorization.substring(7).trim(), false);
        }
        throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing integration token");
    }

    private boolean legacyBearerWindowIsOpen() {
        try {
            return Instant.now().isBefore(Instant.parse(legacyBearerAcceptUntil));
        } catch (Exception exception) {
            log.error("Legacy integration bearer deadline is invalid; fallback is disabled");
            return false;
        }
    }

    private String legacyChecksum(Object payload) {
        if (payload instanceof LeadDtoTransfer lead) {
            return jwtService.generateLegacyChecksum(lead);
        }
        // Legacy sync tokens never bound request bodies; the one-time token fingerprint still prevents replay.
        return "";
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private record IntegrationRule(String method, String subject, String scope, Class<?> payloadType) {
    }

    private record SelectedToken(String value, boolean modern) {
    }

    private static final class AuthException extends RuntimeException {
        private final int status;

        private AuthException(int status, String message) {
            super(message);
            this.status = status;
        }

        private int status() {
            return status;
        }
    }
}
