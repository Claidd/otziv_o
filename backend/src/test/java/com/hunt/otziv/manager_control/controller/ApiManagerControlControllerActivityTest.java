package com.hunt.otziv.manager_control.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.service.ManagerControlService;
import com.hunt.otziv.manager_control.service.ManagerQueueStateService;
import com.hunt.otziv.manager_daily_summary.dto.SiteActivityRequest;
import com.hunt.otziv.manager_daily_summary.service.ManagerSiteActivityService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class ApiManagerControlControllerActivityTest {

    @Mock private ManagerControlService managerControlService;
    @Mock private ManagerQueueStateService queueStateService;
    @Mock private ManagerSiteActivityService managerSiteActivityService;

    private ApiManagerControlController controller;
    private Principal principal;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        controller = new ApiManagerControlController(
                managerControlService,
                new PerformanceMetrics(new SimpleMeterRegistry()),
                queueStateService,
                managerSiteActivityService
        );
        principal = () -> "manager";
        authentication = new UsernamePasswordAuthenticationToken("manager", "n/a", List.of());
    }

    @Test
    void recordsSuccessfulClientMessageAsManagerActivity() {
        ManagerControlConcreteItemResponse response = mock(ManagerControlConcreteItemResponse.class);
        when(managerControlService.sendClientMessage(15L, principal, authentication)).thenReturn(response);

        controller.sendClientMessage(15L, principal, authentication);

        assertActivityType("CLIENT_MESSAGE_SENT");
    }

    @Test
    void recordsSuccessfulWorkerNotificationAsManagerActivity() {
        ManagerControlItemActionRequest request =
                new ManagerControlItemActionRequest("ACTION_TAKEN", null, true);
        ManagerControlConcreteItemResponse response = mock(ManagerControlConcreteItemResponse.class);
        when(response.workerNotificationSentAt()).thenReturn(LocalDateTime.now());
        when(managerControlService.actionConcreteItem(16L, request, principal, authentication))
                .thenReturn(response);

        controller.actionConcreteItem(16L, request, principal, authentication);

        assertActivityType("WORKER_MESSAGE_SENT");
    }

    private void assertActivityType(String expected) {
        ArgumentCaptor<SiteActivityRequest> request = ArgumentCaptor.forClass(SiteActivityRequest.class);
        verify(managerSiteActivityService).record(org.mockito.ArgumentMatchers.eq(principal), request.capture());
        assertEquals(expected, request.getValue().activityType());
        assertEquals("/admin/manager-control", request.getValue().route());
    }
}
