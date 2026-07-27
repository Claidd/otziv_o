package com.hunt.otziv.manager_daily_summary.controller;

import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewManagerSettingRequest;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewManagerSettingResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewSettingsRequest;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewSettingsResponse;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/settings/manager-report-review")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiManagerReportReviewSettingsController {

    private final ManagerReportReviewSettingsService service;

    @GetMapping
    public ManagerReportReviewSettingsResponse settings() {
        return service.settings();
    }

    @PutMapping
    public ManagerReportReviewSettingsResponse update(
            @RequestBody ManagerReportReviewSettingsRequest request
    ) {
        return service.update(request);
    }

    @PutMapping("/managers/{managerId}")
    public ManagerReportReviewManagerSettingResponse updateManager(
            @PathVariable Long managerId,
            @RequestBody ManagerReportReviewManagerSettingRequest request
    ) {
        return service.updateManager(managerId, request != null && request.enabled());
    }
}
