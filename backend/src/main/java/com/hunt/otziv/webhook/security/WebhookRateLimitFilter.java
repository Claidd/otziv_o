package com.hunt.otziv.webhook.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@Slf4j
public class WebhookRateLimitFilter extends OncePerRequestFilter {

    private static final String REGISTRATION_PUBLIC_GROUP = "registration-public";
    private static final Map<String, List<String>> RATE_LIMITED_PATHS = rateLimitedPaths();

    private final WebhookRateLimiter rateLimiter;
    private final WebhookClientIpResolver clientIpResolver;
    private final MeterRegistry meterRegistry;

    @Value("${registration.rate-limit.max-requests:10}")
    private int registrationMaxRequests = 10;

    @Value("${registration.rate-limit.window:PT10M}")
    private Duration registrationWindow = Duration.ofMinutes(10);

    public WebhookRateLimitFilter(
            WebhookRateLimiter rateLimiter,
            WebhookClientIpResolver clientIpResolver,
            MeterRegistry meterRegistry
    ) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String group = rateLimitGroup(request);
        if (group == null || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(request);
        Duration effectiveRegistrationWindow = boundedRegistrationWindow(registrationWindow);
        int effectiveRegistrationLimit = Math.max(1, Math.min(registrationMaxRequests, 1_000));
        boolean accepted = REGISTRATION_PUBLIC_GROUP.equals(group)
                ? rateLimiter.tryAcquire(group + "|" + clientIp, effectiveRegistrationLimit, effectiveRegistrationWindow)
                : rateLimiter.tryAcquire(group + "|" + clientIp);
        if (!accepted) {
            meterRegistry.counter("otziv.http.rate_limit.rejected", "group", group).increment();
            log.debug("Request rate limit exceeded: group={}, ip={}", group, clientIp);
            response.setStatus(429);
            long retryAfter = REGISTRATION_PUBLIC_GROUP.equals(group)
                    ? rateLimiter.retryAfterSeconds(effectiveRegistrationWindow)
                    : rateLimiter.retryAfterSeconds();
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Слишком много запросов. Повторите позже.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String rateLimitGroup(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (hasText(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String normalizedPath = path;
        return RATE_LIMITED_PATHS.entrySet().stream()
                .filter(entry -> !REGISTRATION_PUBLIC_GROUP.equals(entry.getKey())
                        || "POST".equalsIgnoreCase(request.getMethod()))
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(prefix -> matches(entry.getKey(), normalizedPath, prefix)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static boolean matches(String group, String path, String prefix) {
        if (REGISTRATION_PUBLIC_GROUP.equals(group)) {
            return path.equals(prefix) || path.startsWith(prefix + ";");
        }
        return path.equals(prefix)
                || path.startsWith(prefix + "/")
                || path.startsWith(prefix + ";");
    }

    private static Map<String, List<String>> rateLimitedPaths() {
        Map<String, List<String>> paths = new LinkedHashMap<>();
        paths.put("webhook", List.of(
                "/webhook",
                "/api/payments/tbank/webhook",
                "/api/payments/tochka/webhook",
                "/api/leads/import",
                "/api/leads/modified",
                "/api/leads/sync",
                "/api/leads/update",
                "/api/dispatch-settings/cron"
        ));
        paths.put("review-public", List.of(
                "/api/review-check",
                "/api/review-capability",
                "/review/editReviews",
                "/review/editReviewses"
        ));
        paths.put("payment-public", List.of("/api/payments/public"));
        paths.put(REGISTRATION_PUBLIC_GROUP, List.of(
                "/api/auth/register",
                "/api/auth/register-performer",
                "/register"
        ));
        return Map.copyOf(paths);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Duration boundedRegistrationWindow(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            return Duration.ofMinutes(10);
        }
        if (value.compareTo(Duration.ofMinutes(1)) < 0) {
            return Duration.ofMinutes(1);
        }
        return value.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : value;
    }
}
