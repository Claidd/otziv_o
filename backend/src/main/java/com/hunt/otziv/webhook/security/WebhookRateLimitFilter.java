package com.hunt.otziv.webhook.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
@Slf4j
public class WebhookRateLimitFilter extends OncePerRequestFilter {

    private static final List<String> RATE_LIMITED_PATHS = List.of(
            "/webhook",
            "/api/payments/tbank/webhook",
            "/api/review-check",
            "/api/leads/import",
            "/api/leads/modified",
            "/api/leads/sync",
            "/api/leads/update",
            "/api/dispatch-settings/cron"
    );

    private final WebhookRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isWebhookRequest(request) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientIp(request);
        if (!rateLimiter.tryAcquire(clientIp)) {
            log.warn("Webhook rate limit exceeded: ip={}, path={}", clientIp, request.getRequestURI());
            response.setStatus(429);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isWebhookRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (hasText(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String normalizedPath = path;
        return RATE_LIMITED_PATHS.stream()
                .anyMatch(prefix -> normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/"));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (hasText(forwardedFor)) {
            int comma = forwardedFor.indexOf(',');
            return (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (hasText(realIp)) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
