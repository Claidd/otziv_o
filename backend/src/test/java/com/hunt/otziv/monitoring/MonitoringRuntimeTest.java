package com.hunt.otziv.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.hunt.otziv.integration.outbox.service.IntegrationOutboxStatusService;
import com.hunt.otziv.workload_shadow.health.service.WorkloadShadowHealthService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitoringRuntimeTest {
    @Test void requestWindowUsesActualRequestsAndExpiresTrafficRatherThanFakingHealthyZero() throws Exception {
        AtomicLong now=new AtomicLong(1_000_000);var window=new RuntimeRequestWindow(now::get);
        assertThat(window.snapshot().state()).isEqualTo("NO_TRAFFIC");assertThat(window.snapshot().errorRate()).isNull();
        var filter=new RuntimeRequestMetricsFilter(window);
        for(int i=0;i<19;i++)filter.doFilter(new MockHttpServletRequest("GET","/api/fixture"),new MockHttpServletResponse(),(q,s)->{});
        filter.doFilter(new MockHttpServletRequest("GET","/api/fixture"),new MockHttpServletResponse(),(q,s)->((MockHttpServletResponse)s).setStatus(503));
        assertThat(window.snapshot().samples()).isEqualTo(20);assertThat(window.snapshot().errorRate()).isEqualTo(.05);
        filter.doFilter(new MockHttpServletRequest("GET","/api/internal/monitoring/runtime"),new MockHttpServletResponse(),(q,s)->{});
        assertThat(window.snapshot().samples()).isEqualTo(20);
        now.addAndGet(610000);assertThat(window.snapshot().latencyP95Ms()).isNull();
    }
    @Test void overflowLatencyUsesObservedMaximumAndFixedHistogramBounds() {
        var window=new RuntimeRequestWindow();window.record(130_000_000_000L,200);
        assertThat(window.snapshot().latencyP95Ms()).isEqualTo(130000d);
    }
    @Test void asyncRequestIsCountedOnCompletionAndAnErrorIsNotCountedTwice() throws Exception {
        var window=new RuntimeRequestWindow();var filter=new RuntimeRequestMetricsFilter(window);
        var request=new MockHttpServletRequest("GET","/api/async-fixture");request.setAsyncSupported(true);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(q,s)->q.startAsync(q,s));
        assertThat(window.snapshot().samples()).isZero();
        var context=(MockAsyncContext)request.getAsyncContext();
        for(var listener:context.getListeners())listener.onError(new jakarta.servlet.AsyncEvent(context));
        context.complete();
        assertThat(window.snapshot().samples()).isEqualTo(1);assertThat(window.snapshot().errorRate()).isEqualTo(1d);
    }
    @Test void scrapeDoesNotQueryAndMissingSourceIsUnavailable() {
        var outbox=mock(IntegrationOutboxStatusService.class);var workload=mock(WorkloadShadowHealthService.class);
        var service=new MonitoringRuntimeService(new SimpleMeterRegistry(),new MockEnvironment(),new RuntimeRequestWindow(),outbox,workload,
                java.util.List.of(), mock(com.hunt.otziv.config.settings.api.OutboundMessagePolicy.class));
        assertThat(service.snapshot().queues()).allMatch(value->value.state().equals("UNAVAILABLE"));
        verifyNoInteractions(outbox,workload);
        when(outbox.snapshot()).thenThrow(new IllegalStateException("fixture"));when(workload.snapshot()).thenThrow(new IllegalStateException("fixture"));
        service.sampleProjections();assertThat(service.snapshot().queues()).allMatch(value->value.observedAt()==null);
    }
    @Test void durableDeliveryIsSampledOutsideHttpAndFailedInspectionCannotBecomeAnEmptyQueue() {
        var outbox=mock(IntegrationOutboxStatusService.class);var workload=mock(WorkloadShadowHealthService.class);
        var queue=mock(com.hunt.otziv.client_messages.api.DeliveryQueueHealth.class);
        var policy=mock(com.hunt.otziv.config.settings.api.OutboundMessagePolicy.class);
        when(queue.queueName()).thenReturn("manager_client");
        when(queue.deliveryQueueHealth()).thenReturn(java.util.List.of(
                new com.hunt.otziv.client_messages.api.DeliveryQueueHealth.Row(com.hunt.otziv.client_messages.api.DeliveryQueueHealth.State.UNKNOWN, 2, 60, 0),
                new com.hunt.otziv.client_messages.api.DeliveryQueueHealth.Row(com.hunt.otziv.client_messages.api.DeliveryQueueHealth.State.SENDING, 1, 420, 1)));
        var service=new MonitoringRuntimeService(new SimpleMeterRegistry(),new MockEnvironment(),new RuntimeRequestWindow(),outbox,workload,java.util.List.of(queue),policy);
        service.snapshot();verify(queue,never()).deliveryQueueHealth();
        service.sampleProjections();
        var sample=service.snapshot().queues().stream().filter(q->q.name().equals("manager_client")).findFirst().orElseThrow();
        assertThat(sample.state()).isEqualTo("AVAILABLE");assertThat(sample.unknown()).isEqualTo(2);
        assertThat(sample.backlog()).isEqualTo(3);assertThat(sample.oldestDueSeconds()).isEqualTo(420);
        assertThat(sample.dispatchEnabled()).isFalse();verify(queue).deliveryQueueHealth();
        when(queue.deliveryQueueHealth()).thenThrow(new IllegalStateException("fixture"));service.sampleProjections();
        sample=service.snapshot().queues().stream().filter(q->q.name().equals("manager_client")).findFirst().orElseThrow();
        assertThat(sample.state()).isEqualTo("UNAVAILABLE");assertThat(sample.observedAt()).isNull();assertThat(sample.backlog()).isNull();
    }
    @Test void monitorSecretIsMandatoryAndMethodOrAmbientBearerCannotBypassItsBoundary() throws Exception {
        assertThatThrownBy(()->new MonitoringSecurityConfiguration.SecretFilter("")).isInstanceOf(IllegalStateException.class);
        String secret="fixture-monitoring-token-".repeat(3);var filter=new MonitoringSecurityConfiguration.SecretFilter(secret);
        var req=new MockHttpServletRequest("GET",MonitoringSecurityConfiguration.PATH);req.addHeader("Authorization","Bearer unrelated");
        var res=new MockHttpServletResponse();filter.doFilter(req,res,(q,s)->{throw new AssertionError("must refuse");});assertThat(res.getStatus()).isEqualTo(401);
        req=new MockHttpServletRequest("GET",MonitoringSecurityConfiguration.PATH);req.addHeader("X-Otziv-Monitor-Token",secret);res=new MockHttpServletResponse();
        filter.doFilter(req,res,(q,s)->((MockHttpServletResponse)s).setStatus(204));assertThat(res.getStatus()).isEqualTo(204);assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
        req=new MockHttpServletRequest("POST",MonitoringSecurityConfiguration.PATH);req.addHeader("X-Otziv-Monitor-Token",secret);res=new MockHttpServletResponse();
        filter.doFilter(req,res,(q,s)->{throw new AssertionError("method");});assertThat(res.getStatus()).isEqualTo(405);
    }
}
