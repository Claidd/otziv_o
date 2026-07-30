package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadEmergencyAssignmentRepository.NotificationProjection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadEmergencyNotificationStateService {

    private static final int BATCH_SIZE = 25;
    private static final int LEASE_MINUTES = 5;

    private final WorkloadEmergencyAssignmentRepository repository;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional
    public ClaimedNotifications claim() {
        LocalDateTime now = now();
        String token = UUID.randomUUID().toString();
        if (repository.claimNotifications(
                token,
                now,
                now.plusMinutes(LEASE_MINUTES),
                BATCH_SIZE
        ) == 0) {
            return new ClaimedNotifications(token, List.of());
        }
        return new ClaimedNotifications(
                token,
                List.copyOf(repository.findClaimedNotifications(token))
        );
    }

    @Transactional
    public void targetSent(long assignmentId, String token) {
        exact(
                repository.markTargetNotificationSent(
                        assignmentId,
                        token,
                        now()
                )
        );
    }

    @Transactional
    public void auditSent(long assignmentId, String token) {
        exact(
                repository.markAuditNotificationSent(
                        assignmentId,
                        token,
                        now()
                )
        );
    }

    @Transactional
    public void finish(long assignmentId, String token, String lastError) {
        var settings = shadowSettingsService.current();
        LocalDateTime now = now();
        exact(repository.finishNotificationAttempt(
                assignmentId,
                token,
                settings.notificationMaxAttempts(),
                now.plusMinutes(Math.max(
                        1,
                        settings.notificationRetryBaseMinutes()
                )),
                limited(lastError, 1000),
                now
        ));
    }

    private LocalDateTime now() {
        var settings = shadowSettingsService.current();
        return LocalDateTime.now(shadowSettingsService.zone(settings));
    }

    private void exact(int changed) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "Потеряна блокировка аварийного уведомления"
            );
        }
    }

    private String limited(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }

    public record ClaimedNotifications(
            String processingToken,
            List<NotificationProjection> notifications
    ) {
    }
}
