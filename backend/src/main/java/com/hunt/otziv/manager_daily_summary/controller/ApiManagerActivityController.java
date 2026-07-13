package com.hunt.otziv.manager_daily_summary.controller;

import com.hunt.otziv.manager_daily_summary.dto.SiteActivityRequest;
import com.hunt.otziv.manager_daily_summary.service.ManagerSiteActivityService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager-activity")
public class ApiManagerActivityController {

    private final ManagerSiteActivityService activityService;

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@RequestBody(required = false) SiteActivityRequest request, Principal principal) {
        activityService.record(principal, request);
    }
}
