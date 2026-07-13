package com.hunt.otziv.manager_daily_summary.controller;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.dto.ManagerSummaryPreviewResponse;
import com.hunt.otziv.manager_daily_summary.service.ManagerDailySummaryService;
import com.hunt.otziv.manager_daily_summary.service.ManagerSummaryFormatter;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/manager-daily-summary")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiManagerDailySummaryController {

    private final ManagerDailySummaryService summaryService;
    private final ManagerSummaryFormatter formatter;

    @GetMapping
    public List<ManagerDailySummaryResponse> summaries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return summaryService.summaries(date);
    }

    @PostMapping("/calculate")
    public List<ManagerDailySummaryResponse> calculate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean finalizeDay
    ) {
        return summaryService.calculate(date, finalizeDay);
    }

    @GetMapping("/preview")
    public ManagerSummaryPreviewResponse preview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate selected = date == null ? LocalDate.now() : date;
        List<ManagerDailySummaryResponse> rows = summaryService.summaries(selected);
        return new ManagerSummaryPreviewResponse(selected, formatter.format(rows, true), rows);
    }
}
