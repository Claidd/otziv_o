package com.hunt.otziv.config.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Logs every HTTP request slower than the threshold, whatever its endpoint: method, route pattern
 * (never the concrete path or query, so no identifiers or personal data), status, total time and the
 * request's SQL work. The interactive read metrics cover only a fixed set of board pages; this covers
 * status changes, saves, archive searches and every other endpoint. Runs inside the interactive scope
 * when there is one and otherwise opens a plain scope, so SQL is counted for every request.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class SlowHttpRequestLogFilter extends OncePerRequestFilter {

    static final String UNMATCHED = "UNMATCHED";

    private final PerformanceMetrics metrics;
    private final long thresholdNanos;

    public SlowHttpRequestLogFilter(PerformanceMetrics metrics,
                                    @Value("${otziv.slow-log.http-threshold-ms:1000}") long thresholdMs) {
        this.metrics = metrics;
        this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, thresholdMs));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean ownScope = metrics.beginPlainRequest();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            try {
                long elapsed = System.nanoTime() - started;
                if (elapsed >= thresholdNanos) {
                    PerformanceMetrics.RequestStats stats = PerformanceMetrics.currentStats();
                    log.warn("Slow HTTP request method={} route={} status={} totalMs={} sqlCount={} sqlExecuteMs={} connectionMs={} resultNextMs={} rows={}",
                            request.getMethod(), route(request), failed ? 500 : response.getStatus(),
                            TimeUnit.NANOSECONDS.toMillis(elapsed),
                            stats == null ? -1 : stats.queries(), stats == null ? -1 : stats.sqlMs(),
                            stats == null ? -1 : stats.connectionMs(), stats == null ? -1 : stats.fetchMs(),
                            stats == null ? -1 : stats.rows());
                }
            } finally {
                if (ownScope) PerformanceMetrics.endPlainRequest();
            }
        }
    }

    /** The matched route pattern ("/api/manager/orders/{orderId}/status"); requests stopped before routing have none. */
    static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String value = pattern == null ? "" : pattern.toString();
        return value.isBlank() ? UNMATCHED : value;
    }
}
