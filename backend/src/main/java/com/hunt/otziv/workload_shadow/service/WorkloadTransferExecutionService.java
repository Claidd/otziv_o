package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.dto.WorkloadLiveSettingsResponse;
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
                results.add(transactionService.apply(
                        row.getWorkflowId(),
                        row.getWorkflowVersion()
                ));
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

    @Transactional
    public void confirmByOwner(long workflowId) {
        if (workflowId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Workflow не указан"
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
