package com.hunt.otziv.worker_performance.controller;

import com.hunt.otziv.worker_performance.dto.WorkerDayExcuseRequest;
import com.hunt.otziv.worker_performance.dto.WorkerDayExcuseResponse;
import com.hunt.otziv.worker_performance.dto.WorkerDayResultsResponse;
import com.hunt.otziv.worker_performance.service.WorkerDayExcuseService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Final specialist day results and the audited owner action "Засчитать день". */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/worker-day-results")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiAdminWorkerDayResultController {

    private final WorkerDayExcuseService workerDayExcuseService;

    @GetMapping
    public WorkerDayResultsResponse dayResults(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            Authentication authentication
    ) {
        return workerDayExcuseService.dayResults(authentication, date);
    }

    @PostMapping("/{workerId}/{date}/excuse")
    public WorkerDayExcuseResponse excuse(
            @PathVariable Long workerId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestBody(required = false) WorkerDayExcuseRequest request,
            Authentication authentication
    ) {
        return workerDayExcuseService.excuse(authentication, workerId, date, request == null ? null : request.reason());
    }

    @PostMapping("/{workerId}/{date}/excuse/revoke")
    public WorkerDayExcuseResponse revoke(
            @PathVariable Long workerId,
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestBody(required = false) WorkerDayExcuseRequest request,
            Authentication authentication
    ) {
        return workerDayExcuseService.revoke(authentication, workerId, date, request == null ? null : request.reason());
    }
}
