package com.hunt.otziv.metric_snapshots.controller;

import com.hunt.otziv.metric_snapshots.dto.MetricSnapshotSeenRequest;
import com.hunt.otziv.metric_snapshots.service.UserMetricSnapshotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/metric-snapshots")
@PreAuthorize("isAuthenticated()")
public class ApiMetricSnapshotController {

    private final UserMetricSnapshotService snapshotService;

    @PostMapping("/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markSeen(
            Principal principal,
            @Valid @RequestBody MetricSnapshotSeenRequest request
    ) {
        snapshotService.markSeen(principal, request);
    }
}
