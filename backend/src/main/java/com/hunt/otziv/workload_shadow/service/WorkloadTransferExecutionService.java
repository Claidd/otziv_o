package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadLiveControlRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.ReadyWorkflowProjection;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadTransferExecutionService {

    private static final int BATCH_SIZE = 10;

    private final WorkloadTransferExecutionRepository repository;
    private final WorkloadTransferExecutionTransactionService transactionService;
    private final WorkloadTransferExecutionFailureService failureService;
    private final WorkloadLiveSettingsService liveSettingsService;
    private final WorkloadShadowSettingsService shadowSettingsService;
    private final WorkloadLiveControlRepository liveControlRepository;
    private final WorkloadTransferAppliedNotificationService appliedNotificationService;

    public List<WorkloadTransferExecutionTransactionService.ApplyResult>
            applyAcceptedWorkflows() {
        WorkloadLiveSettingsResponse settings = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(settings)) {
            return List.of();
        }
        List<ReadyWorkflowProjection> ready = repository.findReadyWorkflows(BATCH_SIZE);
        List<WorkloadTransferExecutionTransactionService.ApplyResult> results =
                new ArrayList<>();
        for (ReadyWorkflowProjection row : ready) {
            if (row == null || row.getWorkflowId() == null
                    || row.getWorkflowVersion() == null) {
                continue;
            }
            try {
                WorkloadTransferExecutionTransactionService.ApplyResult result =
                        transactionService.apply(
                                row.getWorkflowId(),
                                row.getWorkflowVersion()
                        );
                results.add(result);
                notifyApplied(result);
            } catch (RuntimeException exception) {
                log.error(
                        "Atomic workload transfer failed for workflow {}",
                        row.getWorkflowId(),
                        exception
                );
                failureService.block(
                        row.getWorkflowId(),
                        "EXECUTION_FAILED",
                        rootMessage(exception)
                );
                results.add(new WorkloadTransferExecutionTransactionService.ApplyResult(
                        row.getWorkflowId(),
                        null,
                        "BLOCKED_EXECUTION",
                        "Транзакция полностью отменена: " + rootMessage(exception)
                ));
            }
        }
        return List.copyOf(results);
    }

    private void notifyApplied(
            WorkloadTransferExecutionTransactionService.ApplyResult result
    ) {
        if (result == null
                || result.executionId() == null
                || !"APPLIED".equals(result.status())) {
            return;
        }
        appliedNotificationService.notifyApplied(result.executionId());
    }
    @Transactional
    public void confirmByOwner(long workflowId) {
        if (workflowId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Workflow не указан"
            );
        }
        WorkloadLiveSettingsResponse liveSettings = liveSettingsService.current();
        if (!liveSettingsService.applicationAllowed(liveSettings)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Боевой контур остановлен"
            );
        }
        var liveControl = liveControlRepository.lockState()
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Настройки боевого контура неполны"
                        )
                );
        if (!sameLiveControl(liveSettings, liveControl)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Настройки боевого контура изменились, повторите действие"
            );
        }
        var settings = shadowSettingsService.current();
        int updated = repository.confirmByOwner(
                workflowId,
                java.time.LocalDateTime.now(shadowSettingsService.zone(settings))
        );
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Workflow уже подтверждён, отменён или изменился"
            );
        }
    }

    private boolean sameLiveControl(
            WorkloadLiveSettingsResponse settings,
            WorkloadLiveControlRepository.LiveControlProjection control
    ) {
        if (control.getSettingsRevision() == null
                || control.getSettingsRevision() != settings.revision()) {
            return false;
        }
        String mode = control.getMode() == null ? "" : control.getMode();
        boolean activeMode = "CANARY".equals(mode) || "LIVE".equals(mode);
        return activeMode
                && settings.mode().equals(mode)
                && "true".equalsIgnoreCase(control.getApplyEnabled());
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
