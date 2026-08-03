package com.hunt.otziv.performers.controller;

import com.hunt.otziv.performers.dto.AdminPerformerControlResponse;
import com.hunt.otziv.performers.dto.AdminPerformerManualRunResponse;
import com.hunt.otziv.performers.dto.AdminPerformerResponse;
import com.hunt.otziv.performers.dto.AdminPerformerVerifyAssignmentRequest;
import com.hunt.otziv.performers.dto.PerformerAssignmentResponse;
import com.hunt.otziv.performers.dto.PerformerRolloutSettingsRequest;
import com.hunt.otziv.performers.dto.PerformerRolloutSettingsResponse;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.service.AdminPerformerService;
import com.hunt.otziv.performers.service.PerformerRolloutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/performers")
public class ApiAdminPerformerController {

    private final AdminPerformerService adminPerformerService;
    private final PerformerRolloutService rolloutService;

    @GetMapping("/control")
    public AdminPerformerControlResponse control() {
        return adminPerformerService.control();
    }

    @PostMapping("/{performerId}/status")
    public AdminPerformerResponse updateStatus(
            @PathVariable Long performerId,
            @RequestParam PerformerProfileStatus status,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "false") boolean phoneVerified,
            Principal principal
    ) {
        return adminPerformerService.updateStatus(
                performerId,
                status,
                reason,
                phoneVerified,
                principal == null ? null : principal.getName()
        );
    }

    @PostMapping("/assignments/{assignmentId}/verify")
    public PerformerAssignmentResponse verify(
            @PathVariable Long assignmentId,
            @RequestBody(required = false) AdminPerformerVerifyAssignmentRequest request
    ) {
        return adminPerformerService.verifyAssignment(assignmentId, request);
    }

    @PostMapping(value = "/assignments/{assignmentId}/confirmation-screenshot", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PerformerAssignmentResponse uploadConfirmationScreenshot(
            @PathVariable Long assignmentId,
            @RequestParam("file") MultipartFile file
    ) {
        return adminPerformerService.uploadManagerConfirmationScreenshot(assignmentId, file);
    }

    @PostMapping("/orders/{orderId}/assignments")
    public AdminPerformerManualRunResponse createAssignmentsForOrder(@PathVariable Long orderId) {
        return adminPerformerService.createAssignmentsForOrder(orderId);
    }

    @PostMapping("/scheduler/run")
    public AdminPerformerManualRunResponse runSchedulerOnce() {
        return adminPerformerService.runSchedulerOnce();
    }

    @GetMapping("/rollout")
    public PerformerRolloutSettingsResponse rollout() {
        return rolloutService.settings();
    }

    @PutMapping("/rollout")
    public PerformerRolloutSettingsResponse updateRollout(@RequestBody PerformerRolloutSettingsRequest request) {
        return rolloutService.update(request);
    }
}
