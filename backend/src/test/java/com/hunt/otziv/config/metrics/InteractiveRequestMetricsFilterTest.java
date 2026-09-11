package com.hunt.otziv.config.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractiveRequestMetricsFilterTest {
    @Test void startupZerosAndFirstCallRemainVisibleWithoutASecondScrape() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new PerformanceMetrics(registry);
        metrics.initializeInteractiveMeters();
        assertThat(registry.get("otziv.http.duration").tags("endpoint", "worker.publish", "status", "2xx").timer().count()).isZero();
        var request = new MockHttpServletRequest("GET", "/api/worker/board");
        request.setParameter("section", "publish");
        new InteractiveRequestMetricsFilter(metrics).doFilter(request, new MockHttpServletResponse(), (req, res) ->
                metrics.recordEndpoint("worker.board", () -> PerformanceMetrics.segment("worker.board", "reviews", () -> "ok")));
        assertThat(registry.get("otziv.http.duration").tags("endpoint", "worker.publish", "status", "2xx").timer().count()).isOne();
        assertThat(registry.get("otziv.http.last.request.epoch").tag("endpoint", "worker.publish").gauge().value()).isPositive();
        assertThat(registry.get("otziv.http.phase.duration").tag("phase", "after-controller").timer().count()).isOne();
    }

    @Test void segmentBelongsToTheHttpObservationAndScopeIsClosedAfterFailure() throws Exception {
        var registry = io.micrometer.observation.ObservationRegistry.create();
        var contexts = new java.util.ArrayList<io.micrometer.observation.Observation.Context>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>() {
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) { return true; }
            public void onStop(io.micrometer.observation.Observation.Context context) { contexts.add(context); }
        });
        var metrics = new PerformanceMetrics(new SimpleMeterRegistry());
        metrics.observationRegistry(registry);
        var request = new MockHttpServletRequest("GET", "/api/worker/board");
        assertThatThrownBy(() -> new InteractiveRequestMetricsFilter(metrics).doFilter(request, new MockHttpServletResponse(), (req, res) ->
                PerformanceMetrics.segment("worker.board", "reviews", () -> { throw new IllegalStateException("private"); })))
                .isInstanceOf(IllegalStateException.class);
        assertThat(contexts).hasSize(2);
        assertThat(contexts.getFirst().getParentObservation().getContextView()).isSameAs(contexts.getLast());
        assertThat(contexts.getLast().getLowCardinalityKeyValue("endpoint").getValue()).isEqualTo("worker.new");
        assertThat(registry.getCurrentObservation()).isNull();
        assertThat(PerformanceMetrics.collectingSql()).isFalse();
    }

    @Test void resultIterationIsCountedWithoutRetainingRows() throws Exception {
        var registry = new SimpleMeterRegistry();
        var metrics = new PerformanceMetrics(registry);
        var statement = mock(PreparedStatement.class);
        var rows = mock(java.sql.ResultSet.class);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        var filter = new InteractiveRequestMetricsFilter(metrics);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/worker/board"), new MockHttpServletResponse(), (req, res) -> {
            try {
                var wrapped = ((PreparedStatement) SqlTimingConfiguration.wrapStatement(statement)).executeQuery();
                assertThat(wrapped.next()).isTrue();
                assertThat(wrapped.next()).isFalse();
            } catch (SQLException failure) { throw new jakarta.servlet.ServletException(failure); }
        });
        assertThat(registry.get("otziv.http.sql.executions").summary().totalAmount()).isOne();
        assertThat(registry.get("otziv.http.phase.duration").tag("phase", "result-next").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
    }
    @Test void preflightHeadAndOtherMethodsDoNotDiluteInteractiveGetSlo() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new InteractiveRequestMetricsFilter(new PerformanceMetrics(registry));
        var forwarded = new java.util.concurrent.atomic.AtomicInteger();
        for (String method : new String[]{"OPTIONS", "HEAD", "POST"}) {
            var request = new MockHttpServletRequest(method, "/api/manager/board");
            filter.doFilter(request, new MockHttpServletResponse(), (req,res) -> {
                forwarded.incrementAndGet();
                assertThat(PerformanceMetrics.collectingSql()).isFalse();
            });
        }
        assertThat(forwarded.get()).isEqualTo(3);
        assertThat(registry.getMeters()).isEmpty();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/manager/board"),
                new MockHttpServletResponse(), (req,res) -> {});
        assertThat(registry.get("otziv.http.duration").tag("status", "2xx").timer().count()).isOne();
    }

    @Test void segmentTraceRetainsFailureTypeWithoutPrivateExceptionMessage() {
        var registry=io.micrometer.observation.ObservationRegistry.create();
        var errors=new java.util.ArrayList<Throwable>();
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<io.micrometer.observation.Observation.Context>() {
            public boolean supportsContext(io.micrometer.observation.Observation.Context context) { return true; }
            public void onStop(io.micrometer.observation.Observation.Context context) { if(context.getError()!=null) errors.add(context.getError()); }
        });
        var metrics=new PerformanceMetrics(new SimpleMeterRegistry()); metrics.observationRegistry(registry);
        assertThatThrownBy(() -> metrics.recordSegment("cabinet.profile","load", () -> { throw new IllegalStateException("private-parameter"); }))
                .hasMessage("private-parameter");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().getMessage()).isEqualTo("IllegalStateException");
        assertThat(errors.getFirst().getCause()).isNull();
        assertThat(errors.getFirst().getStackTrace()).isEmpty();
    }

    @Test void includesDeniedRequestsAndNeverLabelsUntrustedValues() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new InteractiveRequestMetricsFilter(new PerformanceMetrics(registry));
        var request = new MockHttpServletRequest("GET", "/api/worker/board");
        request.setParameter("section", "secret-personal-value");
        filter.doFilter(request, new MockHttpServletResponse(), (req,res) -> ((MockHttpServletResponse)res).setStatus(403));
        assertThat(registry.get("otziv.http.duration").tags("endpoint","worker.other","status","4xx").timer().count()).isOne();
        assertThat(registry.getMeters().toString()).doesNotContain("secret-personal-value");
        assertThat(PerformanceMetrics.collectingSql()).isFalse();
        assertThat(InteractiveRequestMetricsFilter.endpoint("/api/manager/board", " ORDERS ")).isEqualTo("manager.orders");
    }

    @Test void propagatesSqlErrorsAndCleansThreadBeforeReuse() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new InteractiveRequestMetricsFilter(new PerformanceMetrics(registry));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenThrow(new SQLException("database-failure"));
        var request = new MockHttpServletRequest("GET", "/api/manager/board");
        request.setParameter("section", "orders");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req,res) -> {
            try { SqlTimingConfiguration.wrap(connection).prepareStatement("private-sql").execute(); }
            catch (SQLException failure) { throw new jakarta.servlet.ServletException(failure); }
        })).hasRootCauseInstanceOf(SQLException.class);
        assertThat(PerformanceMetrics.collectingSql()).isFalse();
        filter.doFilter(request, new MockHttpServletResponse(), (req,res) -> {});
        assertThat(registry.get("otziv.http.sql.executions").tag("status","5xx").summary().count()).isOne();
        assertThat(registry.get("otziv.http.sql.executions").tag("status","5xx").summary().totalAmount()).isOne();
        assertThat(registry.get("otziv.http.sql.executions").tag("status","2xx").summary().count()).isOne();
        assertThat(registry.get("otziv.http.sql.executions").tag("status","2xx").summary().totalAmount()).isZero();
        assertThat(registry.get("otziv.http.duration").tag("status","5xx").timer().count()).isOne();
        assertThat(registry.getMeters().toString()).doesNotContain("private-sql", "database-failure");
    }
}
