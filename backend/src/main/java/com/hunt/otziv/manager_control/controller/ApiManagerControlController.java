package com.hunt.otziv.manager_control.controller;

import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerDetailResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlStageRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlSummaryResponse;
import com.hunt.otziv.manager_control.service.ManagerControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/manager-control")
public class ApiManagerControlController {

    private final ManagerControlService managerControlService;
    private final PerformanceMetrics performanceMetrics;

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerControlSummaryResponse today(
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint(
                "admin.manager-control.today",
                () -> managerControlService.today(principal, authentication)
        );
    }

    @PostMapping("/items/{itemId}/action")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void actionItem(
            @PathVariable Long itemId,
            @RequestBody(required = false) ManagerControlItemActionRequest request,
            Principal principal,
            Authentication authentication
    ) {
        performanceMetrics.recordEndpoint(
                "admin.manager-control.item-action",
                () -> {
                    managerControlService.actionItem(itemId, request, principal, authentication);
                    return null;
                }
        );
    }

    @PostMapping("/concrete-items/{concreteItemId}/action")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerControlConcreteItemResponse actionConcreteItem(
            @PathVariable Long concreteItemId,
            @RequestBody(required = false) ManagerControlItemActionRequest request,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint(
                "admin.manager-control.concrete-item-action",
                () -> managerControlService.actionConcreteItem(concreteItemId, request, principal, authentication)
        );
    }

    @PostMapping("/concrete-items/{concreteItemId}/send-client-message")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerControlConcreteItemResponse sendClientMessage(
            @PathVariable Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint(
                "admin.manager-control.concrete-item-send-client-message",
                () -> managerControlService.sendClientMessage(concreteItemId, principal, authentication)
        );
    }

    @PostMapping("/concrete-items/{concreteItemId}/repair")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerControlConcreteItemResponse repairConcreteItem(
            @PathVariable Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint(
                "admin.manager-control.concrete-item-repair",
                () -> managerControlService.repairConcreteItem(concreteItemId, principal, authentication)
        );
    }

    @GetMapping("/managers/{managerId}/today")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerControlManagerDetailResponse managerDetails(
            @PathVariable Long managerId,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint(
                "admin.manager-control.manager-details",
                () -> managerControlService.managerDetails(managerId, principal, authentication)
        );
    }

    @PostMapping("/controls/{controlId}/stage")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerControlManagerDetailResponse markStage(
            @PathVariable Long controlId,
            @RequestBody(required = false) ManagerControlStageRequest request,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint(
                "admin.manager-control.stage",
                () -> managerControlService.markStage(controlId, request, principal, authentication)
        );
    }

    @PostMapping("/controls/{controlId}/close")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerControlCloseResponse closeDay(
            @PathVariable Long controlId,
            @RequestBody(required = false) ManagerControlCloseRequest request,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint(
                "admin.manager-control.close-day",
                () -> managerControlService.closeDay(controlId, request, principal, authentication)
        );
    }
}
