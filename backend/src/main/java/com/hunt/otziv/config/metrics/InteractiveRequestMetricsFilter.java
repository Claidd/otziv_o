package com.hunt.otziv.config.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fixed route/section allowlist: no names, IDs, query text or credentials in labels. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class InteractiveRequestMetricsFilter extends OncePerRequestFilter {
    private final PerformanceMetrics metrics;
    public InteractiveRequestMetricsFilter(PerformanceMetrics metrics) { this.metrics = metrics; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String endpoint = endpoint(request.getRequestURI(), request.getParameter("section"));
        if (endpoint == null) { chain.doFilter(request, response); return; }
        metrics.beginRequest();
        long started = System.nanoTime();
        boolean failed = true;
        try { chain.doFilter(request, response); failed = false; }
        finally { metrics.finishRequest(endpoint, failed ? 500 : response.getStatus(), System.nanoTime() - started); }
    }
    static String endpoint(String uri, String section) {
        section = section == null ? null : section.trim();
        if ("/api/manager/board".equals(uri)) return "orders".equalsIgnoreCase(section) ? "manager.orders" : "manager.companies";
        if ("/api/worker/board".equals(uri)) {
            String safe = section == null ? "new" : section.toLowerCase(java.util.Locale.ROOT);
            return "worker." + (Set.of("new", "correct", "nagul", "recovery", "publish", "bad", "all", "current").contains(safe) ? safe : "other");
        }
        if (Set.of("/api/cabinet/profile", "/api/cabinet/team", "/api/cabinet/score", "/api/cabinet/analyse").contains(uri))
            return "cabinet." + uri.substring(uri.lastIndexOf('/') + 1);
        if ("/api/admin/manager-control/today".equals(uri)) return "admin.manager-control.today";
        return null;
    }
}
