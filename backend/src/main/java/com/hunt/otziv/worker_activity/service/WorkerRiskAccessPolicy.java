package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkerRiskAccessPolicy {

    private final UserService userService;
    private final WorkerRiskIncidentRepository incidentRepository;
    private final AppSettingService appSettingService;

    @Transactional(readOnly = true)
    public Status status(String username) {
        if (!appSettingService.getBoolean(
                AppSettingService.WORKER_RISK_SPECIALIST_SECTION_RESTRICTION_ENABLED,
                true
        )) {
            return Status.allowed();
        }
        User user = username == null ? null : userService.findByUserName(username).orElse(null);
        if (user == null || user.getId() == null || !isOnlyWorker(user)) {
            return Status.allowed();
        }
        List<WorkerRiskIncident> overdue = incidentRepository
                .findByWorkerUserIdAndStatusAndSectionRestrictedAtIsNotNullAndSectionRestrictionReleasedAtIsNullAndExplanationAcceptedAtIsNullOrderBySectionRestrictedAtAsc(
                        user.getId(),
                        WorkerRiskIncidentStatus.OPEN
                );
        if (overdue.isEmpty()) {
            return Status.allowed();
        }
        WorkerRiskIncident first = overdue.getFirst();
        return new Status(
                true,
                overdue.size(),
                first.getId(),
                first.getResponseDueAt(),
                "Раздел «Специалист» временно ограничен. Ответьте на просроченное замечание в Telegram."
        );
    }

    private boolean isOnlyWorker(User user) {
        boolean worker = hasRole(user, "ROLE_WORKER");
        return worker
                && !hasRole(user, "ROLE_ADMIN")
                && !hasRole(user, "ROLE_OWNER")
                && !hasRole(user, "ROLE_MANAGER");
    }

    private boolean hasRole(User user, String role) {
        return user.getRoles() != null
                && user.getRoles().stream().anyMatch(item -> role.equals(item.getName()));
    }

    public record Status(
            boolean restricted,
            int pendingCount,
            Long oldestIncidentId,
            LocalDateTime oldestDueAt,
            String message
    ) {
        public static Status allowed() {
            return new Status(false, 0, null, null, "");
        }
    }
}
