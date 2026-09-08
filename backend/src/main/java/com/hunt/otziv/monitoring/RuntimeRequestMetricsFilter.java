package com.hunt.otziv.monitoring;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
@ConditionalOnProperty(name = "otziv.monitoring.enabled", havingValue = "true")
public class RuntimeRequestMetricsFilter extends OncePerRequestFilter {
    private final RuntimeRequestWindow window;
    public RuntimeRequestMetricsFilter(RuntimeRequestWindow window) { this.window = window; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.startsWith("/api/internal/monitoring/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime(); AtomicBoolean recorded = new AtomicBoolean();
        Runnable complete = () -> { if (recorded.compareAndSet(false, true)) window.record(System.nanoTime() - started, response.getStatus()); };
        try { chain.doFilter(request, response); }
        catch (ServletException | IOException | RuntimeException failure) {
            if (recorded.compareAndSet(false, true)) window.record(System.nanoTime() - started, 500);
            throw failure;
        } finally {
            if (request.isAsyncStarted()) {
                AsyncListener listener = new AsyncListener() {
                    public void onComplete(AsyncEvent event) { complete.run(); }
                    public void onTimeout(AsyncEvent event) { recordFailure(); }
                    public void onError(AsyncEvent event) { recordFailure(); }
                    private void recordFailure() { if (recorded.compareAndSet(false, true)) window.record(System.nanoTime()-started,500); }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                };
                try { request.getAsyncContext().addListener(listener); } catch (IllegalStateException completed) { complete.run(); }
            } else complete.run();
        }
    }
}
