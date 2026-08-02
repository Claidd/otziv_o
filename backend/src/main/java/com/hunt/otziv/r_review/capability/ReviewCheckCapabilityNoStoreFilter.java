package com.hunt.otziv.r_review.capability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevent caching of bearer-by-link review and payment responses, including early errors. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class ReviewCheckCapabilityNoStoreFilter extends OncePerRequestFilter {

    private static final String CAPABILITY_PATH = "/api/review-capability";
    private static final String REVIEW_CHECK_PATH = "/api/review-check";
    private static final String LEGACY_REVIEW_PATH = "/review/editReviews";
    private static final String LEGACY_CORRECTION_PATH = "/review/editReviewses";
    private static final String PUBLIC_PAYMENT_PATH = "/api/payments/public";
    private static final String CAPABILITY_HEADER = "X-Review-Capability";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = requestPath(request);
        return path == null || !(matches(path, CAPABILITY_PATH)
                || matches(path, REVIEW_CHECK_PATH)
                || matches(path, LEGACY_REVIEW_PATH)
                || matches(path, LEGACY_CORRECTION_PATH)
                || matches(path, PUBLIC_PAYMENT_PATH));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
        if (matches(requestPath(request), CAPABILITY_PATH)) {
            response.addHeader("Vary", CAPABILITY_HEADER);
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String path, String basePath) {
        return path.equals(basePath)
                || path.startsWith(basePath + "/")
                || path.startsWith(basePath + ";");
    }

    private String requestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri != null && contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri == null ? "" : uri;
    }
}
