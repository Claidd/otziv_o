package com.hunt.otziv.outreach_bridge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "outreach-bridge", name = "enabled", havingValue = "true")
public class OutreachBridgeAuthenticationFilter extends OncePerRequestFilter {
    private static final String PATH_PREFIX = "/internal/outreach/v1/";

    private final byte[] expectedDigest;

    public OutreachBridgeAuthenticationFilter(OutreachBridgeProperties properties) {
        expectedDigest = digest(properties.getSharedSecret() == null ? "" : properties.getSharedSecret().trim());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return !path.startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Enumeration<String> values = request.getHeaders(OutreachBridgeProperties.TOKEN_HEADER);
        boolean hasValue = values != null && values.hasMoreElements();
        String supplied = hasValue ? values.nextElement() : "";
        boolean exactlyOne = hasValue && !values.hasMoreElements();
        if (!exactlyOne || !MessageDigest.isEqual(expectedDigest, digest(supplied.trim()))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
