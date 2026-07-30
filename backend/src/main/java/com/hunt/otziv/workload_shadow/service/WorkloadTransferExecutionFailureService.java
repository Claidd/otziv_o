package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadShadowEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadTransferExecutionFailureService {

    private final WorkloadTransferExecutionRepository repository;
    private final WorkloadShadowEventRepository eventRepository;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void block(long workflowId, String code, String message) {
        var settings = shadowSettingsService.current();
        var now = java.time.LocalDateTime.now(
                shadowSettingsService.zone(settings)
        );
        String normalizedCode = trim(code, 80);
        String normalizedMessage = trim(message, 1000);
        exact(
                repository.closeAcceptedCandidateForBlockedWorkflow(
                        workflowId,
                        normalizedMessage,
                        now
                ),
                "Не удалось закрыть кандидата аварийно остановленного workflow"
        );
        exact(
                repository.closeAcceptedOfferForBlockedWorkflow(
                        workflowId,
                        normalizedCode,
                        normalizedMessage,
                        now
                ),
                "Не удалось закрыть предложение аварийно остановленного workflow"
        );
        exact(
                repository.blockWorkflow(
                        workflowId,
                        "BLOCKED_EXECUTION",
                        normalizedCode,
                        normalizedMessage,
                        now
                ),
                "Не удалось аварийно остановить workflow"
        );
        eventRepository.upsertLiveExecutionFailure(
                workflowId,
                normalizedCode,
                normalizedMessage,
                settings.groupNotificationsEnabled(),
                settings.notificationGroupChatId(),
                now,
                now.minusMinutes(Math.max(5, settings.alertCooldownMinutes()))
        );
    }

    private String trim(String value, int maximum) {
        String normalized = value == null || value.isBlank() ? "UNKNOWN" : value.trim();
        return normalized.length() <= maximum
                ? normalized
                : normalized.substring(0, maximum);
    }

    private void exact(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message + ": changed=" + changed);
        }
    }
}
