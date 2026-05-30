package com.hunt.otziv.archive.controller;

import com.hunt.otziv.archive.dto.ArchiveBatchDetails;
import com.hunt.otziv.archive.dto.ArchiveBatchSummary;
import com.hunt.otziv.archive.dto.ArchiveCandidatesPreview;
import com.hunt.otziv.archive.dto.ArchiveDryRunResult;
import com.hunt.otziv.archive.dto.ArchiveLockStatus;
import com.hunt.otziv.archive.dto.ArchiveOrdersSettingsRequest;
import com.hunt.otziv.archive.dto.ArchiveOrdersSettingsResponse;
import com.hunt.otziv.archive.dto.ArchiveRunResult;
import com.hunt.otziv.archive.service.OrderArchiveDryRunService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/archive")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiArchiveController {

    private final OrderArchiveDryRunService archiveDryRunService;

    @GetMapping("/orders/settings")
    public ArchiveOrdersSettingsResponse orderArchiveSettings() {
        return archiveDryRunService.settings();
    }

    @PutMapping("/orders/settings")
    public ArchiveOrdersSettingsResponse updateOrderArchiveSettings(@RequestBody ArchiveOrdersSettingsRequest request) {
        try {
            return archiveDryRunService.updateSettings(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/orders/candidates")
    public ArchiveCandidatesPreview previewOrderCandidates(
            @RequestParam(required = false) Integer retentionDays,
            @RequestParam(required = false) Integer batchLimit,
            @RequestParam(required = false) Integer previewLimit
    ) {
        return archiveDryRunService.previewCandidates(retentionDays, batchLimit, previewLimit);
    }

    @GetMapping("/orders/lock")
    public ArchiveLockStatus orderArchiveLockStatus() {
        return archiveDryRunService.lockStatus();
    }

    @PostMapping("/orders/dry-run")
    public ArchiveDryRunResult dryRunOrders(
            @RequestParam(required = false) Integer retentionDays,
            @RequestParam(required = false) Integer batchLimit,
            @RequestParam(required = false) String reason
    ) {
        return archiveDryRunService.runDryRun(retentionDays, batchLimit, reason);
    }

    @PostMapping("/orders/run")
    public ArchiveRunResult runOrdersArchive(
            @RequestParam(required = false) Integer retentionDays,
            @RequestParam(required = false) Integer batchLimit,
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "false") boolean confirm
    ) {
        try {
            return archiveDryRunService.runArchive(retentionDays, batchLimit, reason, confirm);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, exception.getMessage(), exception);
        }
    }

    @GetMapping("/batches")
    public List<ArchiveBatchSummary> latestBatches(@RequestParam(required = false) Integer limit) {
        return archiveDryRunService.latestBatches(limit);
    }

    @GetMapping("/batches/{batchId}")
    public ArchiveBatchDetails batchDetails(@PathVariable Long batchId) {
        try {
            return archiveDryRunService.batchDetails(batchId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
