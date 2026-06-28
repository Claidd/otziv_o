package com.hunt.otziv.worker_activity.controller;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerRiskEvaluationService;
import com.hunt.otziv.gamification.model.GamificationScoreLedger;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.worker_activity.dto.WorkerRiskIncidentResponse;
import com.hunt.otziv.worker_activity.dto.WorkerRiskResolutionRequest;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import com.hunt.otziv.worker_activity.service.WorkerRiskRollbackService;
import com.hunt.otziv.worker_activity.service.WorkerRiskTelegramCallbackService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/manager/worker-risk")
public class ApiWorkerRiskController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String SOURCE_MANAGER_WARNING = "WORKER_RISK_MANAGER_WARNING";
    private static final String SOURCE_MANAGER_VIOLATION = "WORKER_RISK_MANAGER_VIOLATION";
    private static final String SOURCE_WORKER_EXPLANATION = "WORKER_RISK_WORKER_EXPLANATION";
    private static final String WORKER_RISK_PENALTY_EVENT = "WORKER_RISK_PENALTY";
    private static final int DEFAULT_PENALTY_POINTS = 1;

    private final WorkerRiskIncidentRepository incidentRepository;
    private final GamificationScoreLedgerRepository scoreLedgerRepository;
    private final UserService userService;
    private final PersonalReminderService personalReminderService;
    private final TelegramService telegramService;
    private final WorkerRiskRollbackService rollbackService;

    @GetMapping("/incidents")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public Page<WorkerRiskIncidentResponse> incidents(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication
    ) {
        WorkerRiskIncidentStatus normalizedStatus = parseStatus(status);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        Page<WorkerRiskIncident> incidents;

        if (hasRole(authentication, "ADMIN")) {
            incidents = incidentRepository.findByStatusOrderByCreatedAtDesc(normalizedStatus, pageable);
        } else {
            Set<Long> allowedUserIds = allowedWorkerUserIds(authentication);
            incidents = allowedUserIds.isEmpty()
                    ? Page.empty(pageable)
                    : incidentRepository.findByWorkerUserIdInAndStatusOrderByCreatedAtDesc(
                            allowedUserIds,
                            normalizedStatus,
                            pageable
                    );
        }

        List<WorkerRiskIncidentResponse> content = incidents.getContent().stream()
                .map(WorkerRiskIncidentResponse::from)
                .toList();
        return new PageImpl<>(content, pageable, incidents.getTotalElements());
    }

    @PostMapping("/incidents/{incidentId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public WorkerRiskIncidentResponse resolve(
            @PathVariable Long incidentId,
            Authentication authentication
    ) {
        return applyResolution(incidentId, WorkerRiskResolutionAction.VERIFIED, authentication);
    }

    @PostMapping("/incidents/{incidentId}/ignore")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public WorkerRiskIncidentResponse ignore(
            @PathVariable Long incidentId,
            Authentication authentication
    ) {
        return applyResolution(incidentId, WorkerRiskResolutionAction.FALSE_POSITIVE, authentication);
    }

    @PostMapping("/incidents/{incidentId}/resolution")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public WorkerRiskIncidentResponse resolution(
            @PathVariable Long incidentId,
            @RequestBody WorkerRiskResolutionRequest request,
            Authentication authentication
    ) {
        WorkerRiskResolutionAction action = parseResolutionAction(request == null ? null : request.action());
        int penaltyPoints = request == null ? DEFAULT_PENALTY_POINTS : normalizePenaltyPoints(request.penaltyPoints());
        return applyResolution(incidentId, action, penaltyPoints, authentication);
    }

    @PostMapping("/incidents/{incidentId}/rollback")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public WorkerRiskIncidentResponse rollback(
            @PathVariable Long incidentId,
            Authentication authentication
    ) {
        WorkerRiskIncident incident = findIncidentForCurrentUser(incidentId, authentication);
        User resolver = currentUser(authentication);
        try {
            return WorkerRiskIncidentResponse.from(rollbackService.rollback(incident, resolver));
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private WorkerRiskIncidentResponse applyResolution(
            Long incidentId,
            WorkerRiskResolutionAction action,
            Authentication authentication
    ) {
        return applyResolution(incidentId, action, DEFAULT_PENALTY_POINTS, authentication);
    }

    private WorkerRiskIncidentResponse applyResolution(
            Long incidentId,
            WorkerRiskResolutionAction action,
            int penaltyPoints,
            Authentication authentication
    ) {
        WorkerRiskIncident incident = findIncidentForCurrentUser(incidentId, authentication);

        User resolver = currentUser(authentication);
        incident.setStatus(statusFor(action));
        incident.setResolutionAction(action);
        incident.setResolvedAt(LocalDateTime.now());
        incident.setResolvedByUserId(resolver.getId());
        incident.setResolvedByUsername(resolver.getUsername());
        incident.setPenaltyPoints(action == WorkerRiskResolutionAction.VIOLATION_CONFIRMED ? penaltyPoints : 0);

        if (action == WorkerRiskResolutionAction.EXPLANATION_REQUESTED || action == WorkerRiskResolutionAction.WORKER_WARNED) {
            requestWorkerExplanation(incident);
        } else if (action == WorkerRiskResolutionAction.VIOLATION_CONFIRMED) {
            recordPenalty(incident);
            notifyWorkerViolation(incident);
        }

        WorkerRiskIncident savedIncident = incidentRepository.save(incident);
        deleteResolvedRiskReminders(savedIncident);
        return WorkerRiskIncidentResponse.from(savedIncident);
    }

    private WorkerRiskIncidentStatus statusFor(WorkerRiskResolutionAction action) {
        return switch (action) {
            case FALSE_POSITIVE, NORMAL_ACCOUNT_SELECTION -> WorkerRiskIncidentStatus.IGNORED;
            case EXPLANATION_REQUESTED, WORKER_WARNED -> WorkerRiskIncidentStatus.OPEN;
            case VIOLATION_CONFIRMED -> WorkerRiskIncidentStatus.VIOLATION;
            case VERIFIED -> WorkerRiskIncidentStatus.RESOLVED;
        };
    }

    private void deleteResolvedRiskReminders(WorkerRiskIncident incident) {
        if (incident == null || incident.getStatus() == WorkerRiskIncidentStatus.OPEN) {
            return;
        }

        personalReminderService.deleteSystemRemindersBySource(
                WorkerRiskEvaluationService.SOURCE_WORKER_RISK_INCIDENT,
                incident.getId()
        );
        personalReminderService.deleteSystemRemindersBySource(SOURCE_MANAGER_WARNING, incident.getId());
        personalReminderService.deleteSystemRemindersBySource(SOURCE_WORKER_EXPLANATION, incident.getId());
    }

    private WorkerRiskResolutionAction parseResolutionAction(String action) {
        String normalized = (action == null ? "" : action).trim().toUpperCase(Locale.ROOT);
        if ("WORKER_WARNED".equals(normalized)) {
            return WorkerRiskResolutionAction.EXPLANATION_REQUESTED;
        }
        try {
            return WorkerRiskResolutionAction.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный результат проверки");
        }
    }

    private int normalizePenaltyPoints(Integer penaltyPoints) {
        if (penaltyPoints == null) {
            return DEFAULT_PENALTY_POINTS;
        }
        return Math.max(1, Math.min(100, penaltyPoints));
    }

    private void requestWorkerExplanation(WorkerRiskIncident incident) {
        User worker = userService.findByUserName(incident.getWorkerUsername()).orElse(null);
        if (worker == null || !worker.isActive()) {
            return;
        }
        incident.setExplanationRequestedAt(LocalDateTime.now());

        String text = "Менеджер проверил подозрительное действие и просит дать пояснение."
                + "\nПричина: " + clean(incident.getTitle())
                + "\nДействие: " + clean(incident.getAction())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\n\nПожалуйста, напишите менеджеру, что произошло, и подтвердите фактическое выполнение. "
                + "Рабочие кнопки нужно нажимать только после реального выполнения задачи.";

        if (!personalReminderService.hasOpenSystemReminder(worker, SOURCE_MANAGER_WARNING, incident.getId())) {
            try {
                personalReminderService.createSystemReminderDueNow(
                        worker,
                        "Нужно пояснение по действию",
                        text,
                        SOURCE_MANAGER_WARNING,
                        incident.getId(),
                        incident.getOrderId()
                );
                if (worker.getWorkerTelegramGroupChatId() != null) {
                    telegramService.sendMessageWithInlineKeyboard(
                            worker.getWorkerTelegramGroupChatId(),
                            text,
                            null,
                            WorkerRiskTelegramCallbackService.explanationKeyboard(incident.getId())
                    );
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось отправить запрос пояснения по риск-инциденту incidentId={}, workerUserId={}",
                        incident.getId(),
                        incident.getWorkerUserId(),
                        exception);
            }
        }
    }

    private void notifyWorkerViolation(WorkerRiskIncident incident) {
        User worker = userService.findByUserName(incident.getWorkerUsername()).orElse(null);
        if (worker == null || !worker.isActive()) {
            return;
        }

        String text = "Менеджер подтвердил нарушение по подозрительному действию."
                + "\nПричина: " + clean(incident.getTitle())
                + "\nДействие: " + clean(incident.getAction())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\nШтрафные баллы: " + incident.getPenaltyPoints()
                + "\n\nЕсли задача сделана неверно, менеджер может вернуть поддерживаемые карточки в работу "
                + "из раздела рисков. Для остальных случаев дождитесь указаний менеджера.";

        if (!personalReminderService.hasOpenSystemReminder(worker, SOURCE_MANAGER_VIOLATION, incident.getId())) {
            try {
                personalReminderService.createSystemReminderDueNow(
                        worker,
                        "Подтверждено нарушение",
                        text,
                        SOURCE_MANAGER_VIOLATION,
                        incident.getId(),
                        incident.getOrderId()
                );
                if (worker.getWorkerTelegramGroupChatId() != null) {
                    telegramService.sendMessage(worker.getWorkerTelegramGroupChatId(), text);
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось отправить уведомление о нарушении incidentId={}, workerUserId={}",
                        incident.getId(),
                        incident.getWorkerUserId(),
                        exception);
            }
        }
    }

    private void recordPenalty(WorkerRiskIncident incident) {
        String uniqueScoreKey = "worker-risk-penalty:" + incident.getId();
        if (scoreLedgerRepository.existsByUniqueScoreKey(uniqueScoreKey)) {
            return;
        }
        int penaltyPoints = Math.max(1, incident.getPenaltyPoints());
        scoreLedgerRepository.save(GamificationScoreLedger.builder()
                .eventType(WORKER_RISK_PENALTY_EVENT)
                .actorUserId(incident.getWorkerUserId())
                .actorRole("WORKER")
                .actorName(firstNonBlank(incident.getWorkerName(), incident.getWorkerUsername()))
                .points(-penaltyPoints)
                .rulePoints(-penaltyPoints)
                .basePoints(0)
                .orderId(incident.getOrderId())
                .reviewId(incident.getReviewId())
                .uniqueScoreKey(uniqueScoreKey)
                .sourceEventCreatedAt(incident.getResolvedAt() == null ? LocalDateTime.now() : incident.getResolvedAt())
                .build());
    }

    private WorkerRiskIncidentStatus parseStatus(String status) {
        try {
            return WorkerRiskIncidentStatus.valueOf((status == null ? "OPEN" : status).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный статус инцидента");
        }
    }

    private Set<Long> allowedWorkerUserIds(Authentication authentication) {
        User user = currentUser(authentication);
        if (hasRole(authentication, "OWNER")) {
            Set<Manager> managers = userService.findManagersByUserName(user.getUsername());
            return userService.findAllRelevantUserIdsForOwner(managers);
        }
        if (hasRole(authentication, "MANAGER")) {
            return Set.copyOf(userService.findAllRelevantUserIdsForManagerIds(
                    userService.findManagerIdsByUserId(user.getId())
            ));
        }
        return Set.of();
    }

    private WorkerRiskIncident findIncidentForCurrentUser(Long incidentId, Authentication authentication) {
        WorkerRiskIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Инцидент не найден"));
        if (!hasRole(authentication, "ADMIN") && !allowedWorkerUserIds(authentication).contains(incident.getWorkerUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Инцидент не найден");
        }
        return incident;
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден");
        }
        return userService.findByUserName(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
