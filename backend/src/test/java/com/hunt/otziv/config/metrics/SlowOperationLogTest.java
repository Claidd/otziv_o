package com.hunt.otziv.config.metrics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Slow requests on any endpoint and slow scheduled tasks reach the log (and Grafana) with their SQL work. */
class SlowOperationLogTest {

    private final ListAppender<ILoggingEvent> httpLog = new ListAppender<>();
    private final ListAppender<ILoggingEvent> taskLog = new ListAppender<>();

    @BeforeEach
    void attach() {
        httpLog.start();
        taskLog.start();
        ((Logger) LoggerFactory.getLogger(SlowHttpRequestLogFilter.class)).addAppender(httpLog);
        ((Logger) LoggerFactory.getLogger(SlowScheduledTaskLogHandler.class)).addAppender(taskLog);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(SlowHttpRequestLogFilter.class)).detachAppender(httpLog);
        ((Logger) LoggerFactory.getLogger(SlowScheduledTaskLogHandler.class)).detachAppender(taskLog);
    }

    @Test
    void aSlowRequestOnAnyEndpointIsLoggedWithItsRouteAndSqlButNoIdentifiers() throws Exception {
        var metrics = new PerformanceMetrics(new SimpleMeterRegistry());
        var filter = new SlowHttpRequestLogFilter(metrics, 1);
        var request = new MockHttpServletRequest("POST", "/api/manager/orders/28379/status");
        request.setQueryString("title=secret");
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("select 1")).thenReturn(statement);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            // The handler mapping sets the pattern; SQL inside the request is counted.
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/manager/orders/{orderId}/status");
            try {
                SqlTimingConfiguration.wrap(connection).prepareStatement("select 1").execute();
                TimeUnit.MILLISECONDS.sleep(3);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            ((MockHttpServletResponse) res).setStatus(204);
        });

        assertThat(httpLog.list).hasSize(1);
        String line = httpLog.list.getFirst().getFormattedMessage();
        assertThat(line).startsWith("Slow HTTP request method=POST route=/api/manager/orders/{orderId}/status status=204 totalMs=")
                .contains("sqlCount=1").doesNotContain("28379").doesNotContain("secret");
        // The plain scope is closed: nothing leaks into the next request on this thread.
        assertThat(PerformanceMetrics.collectingSql()).isFalse();
    }

    @Test
    void fastRequestsAreNotLoggedAndAnInteractiveScopeIsReused() throws Exception {
        var metrics = new PerformanceMetrics(new SimpleMeterRegistry());
        new SlowHttpRequestLogFilter(metrics, 60_000).doFilter(new MockHttpServletRequest("GET", "/api/x"),
                new MockHttpServletResponse(), (req, res) -> { });
        assertThat(httpLog.list).isEmpty();

        // Inside the interactive board scope the filter only reads that scope's counters.
        metrics.initializeInteractiveMeters();
        var interactive = new InteractiveRequestMetricsFilter(metrics);
        var request = new MockHttpServletRequest("GET", "/api/worker/board");
        request.setParameter("section", "publish");
        interactive.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                new SlowHttpRequestLogFilter(metrics, 1).doFilter((jakarta.servlet.http.HttpServletRequest) req,
                        (jakarta.servlet.http.HttpServletResponse) res, (inner, innerResponse) -> {
                            assertThat(PerformanceMetrics.collectingSql()).isTrue();
                            try { TimeUnit.MILLISECONDS.sleep(3); } catch (InterruptedException ignored) { }
                        }));
        assertThat(httpLog.list).hasSize(1);
        assertThat(httpLog.list.getFirst().getFormattedMessage()).contains("route=UNMATCHED").contains("sqlCount=0");
        assertThat(PerformanceMetrics.collectingSql()).isFalse();
    }

    @Test
    void aSlowScheduledTaskIsLoggedOncePerQuietPeriodWithTheSkippedCount() {
        AtomicLong clock = new AtomicLong();
        var handler = new SlowScheduledTaskLogHandler(30_000, 15, clock::get);
        assertThat(handler.supportsContext(new Observation.Context())).isFalse();

        run(handler, clock, 31_000);
        run(handler, clock, 45_000);
        run(handler, clock, 5_000);
        assertThat(taskLog.list).hasSize(1);
        assertThat(taskLog.list.getFirst().getFormattedMessage())
                .isEqualTo("Slow scheduled task task=ScheduledClientMessageService.tick durationMs=31000 outcome=SUCCESS slowRunsSinceLastLog=0");

        clock.addAndGet(TimeUnit.MINUTES.toNanos(16));
        run(handler, clock, 40_000);
        assertThat(taskLog.list).hasSize(2);
        assertThat(taskLog.list.get(1).getFormattedMessage()).contains("durationMs=40000").endsWith("slowRunsSinceLastLog=1");
    }

    private static void run(SlowScheduledTaskLogHandler handler, AtomicLong clock, long millis) {
        Observation.Context context = new Observation.Context();
        context.setName(SlowScheduledTaskLogHandler.SCHEDULED_TASK_OBSERVATION);
        context.addLowCardinalityKeyValues(io.micrometer.common.KeyValues.of(List.of(
                KeyValue.of("code.namespace", "com.hunt.otziv.client_messages.service.ScheduledClientMessageService$$SpringCGLIB$$0"),
                KeyValue.of("code.function", "tick"),
                KeyValue.of("outcome", "SUCCESS"))));
        assertThat(handler.supportsContext(context)).isTrue();
        handler.onStart(context);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
        handler.onStop(context);
    }
}
