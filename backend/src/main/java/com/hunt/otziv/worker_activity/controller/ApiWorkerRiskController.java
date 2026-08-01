package com.hunt.otziv.worker_activity.controller;

import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerRiskEvaluationService;
import com.hunt.otziv.gamification.model.GamificationScoreLedger;
import com.hunt.otziv.gamification.repository.GamificationScoreLedgerRepository;
import com.hunt.otziv.worker_activity.dto.WorkerRiskIncidentResponse;
import com.hunt.otziv.worker_activity.dto.WorkerRiskAuditRequest;
import com.hunt.otziv.worker_activity.dto.WorkerRiskResolutionRequest;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskEventType;
import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import com.hunt.otziv.worker_activity.service.WorkerRiskRollbackService;
import com.hunt.otziv.worker_activity.service.WorkerRiskEventService;
import com.hunt.otziv.worker_activity.service.WorkerRiskDecisionPolicy;
import com.hunt.otziv.worker_activity.service.WorkerRiskTelegramCallbackService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
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
import org.springframework.transaction.annotation.Transactional;
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
    private final ManagerDailyControlConcreteItemRepository managerControlConcreteItemRepository;
    private final WorkerRiskEventService riskEventService;
    private final WorkerRiskDecisionPolicy decisionPolicy;
    private final AppSettingService appSettingService;
    private final WorkerRiskTelegramCallbackService workerRiskTelegramCallbackService;

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
        } else if (hasRole(authentication, "MANAGER")) {
            User current = currentUser(authentication);
            List<Long> managerIds = userService.findManagerIdsByUserId(current.getId());
            Set<Long> legacyWorkerIds = allowedWorkerUserIds(authentication);
            incidents = managerIds.isEmpty()
                    ? Page.empty(pageable)
                    : incidentRepository.findVisibleForManager(
                            managerIds,
                            legacyWorkerIds.isEmpty() ? Set.of(-1L) : legacyWorkerIds,
                            normalizedStatus,
                            pageable
                    );
        } else {
            List<Long> managerIds = visibleManagerIds(authentication);
            Set<Long> allowedUserIds = allowedWorkerUserIds(authentication);
            incidents = managerIds.isEmpty()
                    ? allowedUserIds.isEmpty()
                            ? Page.empty(pageable)
                            : incidentRepository.findByWorkerUserIdInAndStatusOrderByCreatedAtDesc(
                                    allowedUserIds,
                                    normalizedStatus,
                                    pageable
                            )
                    : incidentRepository.findVisibleForManager(
                            managerIds,
                            allowedUserIds.isEmpty() ? Set.of(-1L) : allowedUserIds,
                            normalizedStatus,
                            pageable
                    );
        }

        List<WorkerRiskIncidentResponse> content = incidents.getContent().stream()
                .map(WorkerRiskIncidentResponse::from)
                .toList();
        return new PageImpl<>(content, pageable, incidents.getTotalElements());
    }

    @GetMapping("/incidents/audit")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public Page<WorkerRiskIncidentResponse> auditIncidents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        Page<WorkerRiskIncident> incidents;
        if (hasRole(authentication, "ADMIN")) {
            incidents = incidentRepository.findByAuditRequiredTrueOrderByResolvedAtDescCreatedAtDesc(pageable);
        } else if (hasRole(authentication, "OWNER")) {
            List<Long> managerIds = visibleManagerIds(authentication);
            Set<Long> workerIds = allowedWorkerUserIds(authentication);
            incidents = managerIds.isEmpty()
                    ? workerIds.isEmpty()
                            ? Page.empty(pageable)
                            : incidentRepository.findByWorkerUserIdInAndAuditRequiredTrueOrderByResolvedAtDescCreatedAtDesc(
                                    workerIds,
                                    pageable
                            )
                    : incidentRepository.findAuditVisibleForManager(
                            managerIds,
                            workerIds.isEmpty() ? Set.of(-1L) : workerIds,
                            pageable
                    );
        } else {
            List<Long> managerIds = userService.findManagerIdsByUserId(currentUser(authentication).getId());
            Set<Long> legacyWorkerIds = allowedWorkerUserIds(authentication);
            incidents = managerIds.isEmpty()
                    ? Page.empty(pageable)
                    : incidentRepository.findAuditVisibleForManager(
                            managerIds,
                            legacyWorkerIds.isEmpty() ? Set.of(-1L) : legacyWorkerIds,
                            pageable
                    );
        }
        return incidents.map(WorkerRiskIncidentResponse::from);
    }

    @PostMapping("/incidents/{incidentId}/audit")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @Transactional
    public WorkerRiskIncidentResponse reviewAudit(
            @PathVariable Long incidentId,
            @RequestBody WorkerRiskAuditRequest request,
            Authentication authentication
    ) {
        WorkerRiskIncident incident = findIncidentForCurrentUser(incidentId, authentication);
        if (!incident.isAuditRequired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Этот риск уже не требует аудита");
        }
        if (!decisionPolicy.isFinalAction(incident.getResolutionAction())
                || incident.getStatus() == WorkerRiskIncidentStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сначала менеджер должен принять итоговое решение по риску"
            );
        }
        String decision = clean(request == null ? null : request.decision()).toUpperCase(Locale.ROOT);
        String comment = clean(request == null ? null : request.comment());
        if (comment.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите результат проверки и подтверждённые факты");
        }
        switch (decision) {
            case "CONFIRMED" -> {
                incident.setDecisionQuality("OWNER_CONFIRMED");
                incident.setDecisionQualityReason(comment);
                incident.setAuditRequired(false);
            }
            case "RETURNED" -> {
                incident.setStatus(WorkerRiskIncidentStatus.OPEN);
                incident.setResolvedAt(null);
                incident.setResolvedByUserId(null);
                incident.setResolvedByUsername(null);
                incident.setResolutionAction(null);
                incident.setResponseDueAt(null);
                if (incident.getSectionRestrictedAt() != null
                        && incident.getSectionRestrictionReleasedAt() == null) {
                    incident.setSectionRestrictionReleasedAt(LocalDateTime.now());
                }
                incident.setDecisionQuality("OWNER_RETURNED");
                incident.setDecisionQualityReason(comment);
                incident.setAuditRequired(false);
            }
            case "SYSTEM_ERROR" -> {
                incident.setStatus(WorkerRiskIncidentStatus.IGNORED);
                incident.setResolutionAction(WorkerRiskResolutionAction.FALSE_POSITIVE);
                incident.setDecisionQuality("SYSTEM_ERROR");
                incident.setDecisionQualityReason(comment);
                incident.setAuditRequired(false);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный результат аудита");
        }
        clearSlaDeliveryClaim(incident);
        User reviewer = currentUser(authentication);
        WorkerRiskIncident saved = incidentRepository.save(incident);
        riskEventService.record(
                saved,
                WorkerRiskEventType.AUDIT_REVIEWED,
                reviewer.getId(),
                hasRole(authentication, "ADMIN") ? "ADMIN" : "OWNER",
                "site",
                Map.of("decision", decision, "comment", comment)
        );
        return WorkerRiskIncidentResponse.from(saved);
    }

    @PostMapping("/incidents/{incidentId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @Transactional
    public WorkerRiskIncidentResponse resolve(
            @PathVariable Long incidentId,
            Authentication authentication
    ) {
        return applyResolution(incidentId, WorkerRiskResolutionAction.VERIFIED, authentication);
    }

    @PostMapping("/incidents/{incidentId}/ignore")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @Transactional
    public WorkerRiskIncidentResponse ignore(
            @PathVariable Long incidentId,
            Authentication authentication
    ) {
        return applyResolution(incidentId, WorkerRiskResolutionAction.FALSE_POSITIVE, authentication);
    }

    @PostMapping("/incidents/{incidentId}/resolution")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @Transactional
    public WorkerRiskIncidentResponse resolution(
            @PathVariable Long incidentId,
            @RequestBody WorkerRiskResolutionRequest request,
            Authentication authentication
    ) {
        WorkerRiskResolutionAction action = parseResolutionAction(request == null ? null : request.action());
        int penaltyPoints = request == null ? DEFAULT_PENALTY_POINTS : normalizePenaltyPoints(request.penaltyPoints());
        return applyResolution(incidentId, action, penaltyPoints, clean(request == null ? null : request.comment()), authentication);
    }

    @PostMapping("/incidents/{incidentId}/rollback")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @Transactional
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
        return applyResolution(incidentId, action, DEFAULT_PENALTY_POINTS, "", authentication);
    }

    private WorkerRiskIncidentResponse applyResolution(
            Long incidentId,
            WorkerRiskResolutionAction action,
            int penaltyPoints,
            String comment,
            Authentication authentication
    ) {
        WorkerRiskIncident incident = findIncidentForCurrentUser(incidentId, authentication);
        boolean privilegedVerification = action == WorkerRiskResolutionAction.VERIFIED
                && (hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER"));
        if (!privilegedVerification) {
            decisionPolicy.requireAllowed(incident, action, comment);
        }

        User resolver = currentUser(authentication);
        incident.setStatus(statusFor(action));
        incident.setResolutionAction(action);
        incident.setResolvedAt(decisionPolicy.isFinalAction(action) ? LocalDateTime.now() : null);
        incident.setResolvedByUserId(resolver.getId());
        incident.setResolvedByUsername(resolver.getUsername());
        incident.setPenaltyPoints(action == WorkerRiskResolutionAction.VIOLATION_CONFIRMED ? penaltyPoints : 0);
        appendManagerResolutionComment(incident, action, comment, resolver);
        if (privilegedVerification) {
            decisionPolicy.applyPrivilegedVerification(
                    incident,
                    comment,
                    hasRole(authentication, "ADMIN") ? "ADMIN" : "OWNER"
            );
        } else {
            decisionPolicy.applyDecisionEvidence(incident, action, comment);
        }
        boolean restrictionReleased = decisionPolicy.isFinalAction(action)
                && incident.getSectionRestrictedAt() != null
                && incident.getSectionRestrictionReleasedAt() == null;
        if (restrictionReleased) {
            incident.setSectionRestrictionReleasedAt(LocalDateTime.now());
        }
        if (decisionPolicy.isFinalAction(action)) {
            clearSlaDeliveryClaim(incident);
        }

        if (action == WorkerRiskResolutionAction.EXPLANATION_REQUESTED || action == WorkerRiskResolutionAction.WORKER_WARNED) {
            requestWorkerExplanation(incident);
        } else if (action == WorkerRiskResolutionAction.VIOLATION_CONFIRMED) {
            recordPenalty(incident);
            notifyWorkerViolation(incident);
        }

        WorkerRiskIncident savedIncident = incidentRepository.save(incident);
        riskEventService.record(
                savedIncident,
                hasRole(authentication, "ADMIN")
                        ? WorkerRiskEventType.ADMIN_OVERRIDE
                        : WorkerRiskEventType.MANAGER_DECISION,
                resolver.getId(),
                hasRole(authentication, "ADMIN") ? "ADMIN" : hasRole(authentication, "OWNER") ? "OWNER" : "MANAGER",
                "site",
                Map.of(
                        "action", action.name(),
                        "status", savedIncident.getStatus().name(),
                        "comment", clean(comment),
                        "decisionQuality", firstNonBlank(savedIncident.getDecisionQuality(), "")
                )
        );
        if (restrictionReleased) {
            riskEventService.record(
                    savedIncident,
                    WorkerRiskEventType.SPECIALIST_SECTION_RELEASED,
                    resolver.getId(),
                    hasRole(authentication, "ADMIN") ? "ADMIN" : hasRole(authentication, "OWNER") ? "OWNER" : "MANAGER",
                    "site",
                    Map.of("reason", "manager-final-decision")
            );
        }
        deleteResolvedRiskReminders(savedIncident);
        if (decisionPolicy.isFinalAction(action)) {
            workerRiskTelegramCallbackService.markOriginalRiskTelegramMessageResolved(savedIncident);
        }
        return WorkerRiskIncidentResponse.from(savedIncident);
    }

    private void appendManagerResolutionComment(
            WorkerRiskIncident incident,
            WorkerRiskResolutionAction action,
            String comment,
            User resolver
    ) {
        String text = clean(comment);
        if (incident == null || text.isBlank()) {
            return;
        }
        String actionText = switch (action) {
            case VERIFIED -> "Проверено";
            case FALSE_POSITIVE -> "Игнор";
            case VIOLATION_CONFIRMED -> "Нарушение / штраф";
            case EXPLANATION_REQUESTED, WORKER_WARNED -> "Запрос пояснения";
            case NORMAL_ACCOUNT_SELECTION -> "Нормальный выбор аккаунта";
        };
        String existing = clean(incident.getDetails());
        String addition = "Комментарий менеджера (" + actionText + ", "
                + firstNonBlank(resolver == null ? null : resolver.getFio(), resolver == null ? null : resolver.getUsername())
                + "): " + text;
        incident.setDetails(existing.isBlank() ? addition : existing + "\n\n" + addition);
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
        User worker = workerFor(incident);
        if (worker == null || !worker.isActive()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (incident.getExplanationRequestedAt() == null) {
            incident.setExplanationRequestedAt(now);
        }

        String text = "Менеджер проверил подозрительное действие и просит дать пояснение."
                + "\nСтатус: ждем пояснение"
                + "\nПричина: " + clean(incident.getTitle())
                + "\nДействие: " + clean(incident.getAction())
                + "\nЗаказ: #" + valueOrDash(incident.getOrderId())
                + "\nОтзыв: #" + valueOrDash(incident.getReviewId())
                + "\n\nПожалуйста, напишите менеджеру, что произошло, и подтвердите фактическое выполнение. "
                + "Рабочие кнопки нужно нажимать только после реального выполнения задачи.";

        boolean telegramAttempted = false;
        boolean telegramSent = false;
        String failureReason = null;
        boolean personalTelegramMissing = worker.getTelegramChatId() == null;
        boolean responseRestrictionReleased = personalTelegramMissing
                && incident.getSectionRestrictedAt() != null
                && incident.getSectionRestrictionReleasedAt() == null;
        if (personalTelegramMissing) {
            incident.setResponseDueAt(null);
            incident.setExplanationReminderAt(null);
            clearSlaDeliveryClaim(incident);
            if (responseRestrictionReleased) {
                incident.setSectionRestrictionReleasedAt(now);
            }
        }
        try {
            if (!personalReminderService.hasOpenSystemReminder(worker, SOURCE_MANAGER_WARNING, incident.getId())) {
                personalReminderService.createSystemReminderDueNow(
                        worker,
                        "Нужно пояснение по действию",
                        text,
                        SOURCE_MANAGER_WARNING,
                        incident.getId(),
                        incident.getOrderId()
                );
            }
            if (personalTelegramMissing) {
                failureReason = "Личный Telegram специалиста не привязан";
                if (worker.getWorkerTelegramGroupChatId() != null) {
                    telegramService.sendMessage(
                            worker.getWorkerTelegramGroupChatId(),
                            "⚠️ Пояснение пока нельзя принять: личный Telegram специалиста не привязан."
                                    + "\nТрёхчасовой срок и ограничение раздела не запущены."
                                    + "\nОбратитесь к администратору для безопасной привязки."
                                    + "\nНе отправляйте логин в общий чат."
                                    + "\nКод риска: risk-" + incident.getId()
                    );
                }
                if (responseRestrictionReleased) {
                    riskEventService.record(
                            incident,
                            WorkerRiskEventType.SPECIALIST_SECTION_RELEASED,
                            worker.getId(),
                            "WORKER",
                            "telegram",
                            Map.of("reason", "response-channel-unavailable")
                    );
                }
            } else if (incident.getResponseDueAt() == null) {
                Long telegramChatId = worker.getWorkerTelegramGroupChatId() != null
                        ? worker.getWorkerTelegramGroupChatId()
                        : worker.getTelegramChatId();
                if (telegramChatId == null) {
                    failureReason = "Telegram специалиста не привязан";
                } else {
                    telegramAttempted = true;
                    telegramSent = telegramService.sendMessageWithInlineKeyboard(
                            telegramChatId,
                            text,
                            null,
                            WorkerRiskTelegramCallbackService.explanationKeyboard(incident.getId())
                    );
                    if (telegramSent) {
                        incident.setExplanationPromptedAt(now);
                        incident.setExplanationReminderAt(null);
                        clearSlaDeliveryClaim(incident);
                        if (incident.getSectionRestrictionReleasedAt() != null) {
                            incident.setSectionRestrictedAt(null);
                            incident.setSectionRestrictionReleasedAt(null);
                        }
                        int deadlineMinutes = Math.max(1, appSettingService.getInt(
                                AppSettingService.WORKER_RISK_EXPLANATION_DEADLINE_MINUTES,
                                180
                        ));
                        incident.setResponseDueAt(now.plusMinutes(deadlineMinutes));
                        riskEventService.record(
                                incident,
                                WorkerRiskEventType.EXPLANATION_REQUEST_SENT,
                                worker.getId(),
                                "WORKER",
                                "telegram",
                                Map.of("chatId", telegramChatId, "responseDueAt", incident.getResponseDueAt().toString())
                        );
                    } else {
                        failureReason = "Telegram не отправил сообщение";
                    }
                }
            }
        } catch (RuntimeException exception) {
            failureReason = "Ошибка отправки Telegram: " + exception.getMessage();
            log.warn("Не удалось отправить запрос пояснения по риск-инциденту incidentId={}, workerUserId={}",
                    incident.getId(),
                    incident.getWorkerUserId(),
                    exception);
        }
        if (failureReason != null) {
            riskEventService.record(
                    incident,
                    WorkerRiskEventType.EXPLANATION_REQUEST_FAILED,
                    worker.getId(),
                    "WORKER",
                    "telegram",
                    Map.of("reason", failureReason)
            );
        }
        syncManagerControlRiskRequest(incident, worker, now, telegramAttempted, telegramSent, failureReason);
    }

    private void syncManagerControlRiskRequest(
            WorkerRiskIncident incident,
            User worker,
            LocalDateTime requestedAt,
            boolean telegramAttempted,
            boolean telegramSent,
            String failureReason
    ) {
        if (incident == null || incident.getId() == null || worker == null) {
            return;
        }
        List<ManagerDailyControlConcreteItem> items = managerControlConcreteItemRepository
                .findByEntityTypeAndEntityId("RISK", incident.getId());
        for (ManagerDailyControlConcreteItem item : items) {
            if (item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
                continue;
            }
            item.setWorkerNotificationUserId(worker.getId());
            item.setWorkerNotificationAttemptedAt(telegramAttempted ? requestedAt : null);
            item.setWorkerNotificationSentAt(telegramSent ? requestedAt : null);
            item.setWorkerNotificationAcceptedAt(null);
            item.setWorkerNotificationAcceptedByUserId(null);
            item.setWorkerNotificationFailureReason(telegramSent ? null : failureReason);
            item.setWorkerExplanationRequestedAt(requestedAt);
            item.setWorkerExplanationPromptedAt(null);
            item.setWorkerExplanation(null);
            item.setWorkerExplanationAt(null);
            item.setWorkerExplanationByUserId(null);
            item.setWorkerReminderSentAt(null);
            item.setWorkerReminderCount(0);
            item.setLastManualTouchAt(requestedAt);
            boolean deliveryFailed = failureReason != null && !telegramSent;
            if (deliveryFailed) {
                item.setStatus(ManagerDailyControlItemStatus.OPEN);
                item.setActionType(null);
                item.setFollowUpAt(null);
            } else {
                item.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
                item.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
                item.setFollowUpAt(requestedAt == null ? null : requestedAt.plusHours(3));
            }
            managerControlConcreteItemRepository.save(item);
        }
    }

    private void notifyWorkerViolation(WorkerRiskIncident incident) {
        User worker = workerFor(incident);
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
        WorkerRiskIncident incident = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Инцидент не найден"));
        List<Long> currentManagerIds = visibleManagerIds(authentication);
        if (!hasRole(authentication, "ADMIN")
                && !allowedWorkerUserIds(authentication).contains(incident.getWorkerUserId())
                && (incident.getAssignedManagerId() == null
                        || !currentManagerIds.contains(incident.getAssignedManagerId()))
                && !isVisibleInCurrentManagerControl(incidentId, authentication)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Инцидент не найден");
        }
        return incident;
    }

    private List<Long> visibleManagerIds(Authentication authentication) {
        if (hasRole(authentication, "OWNER")) {
            return userService.findManagersByUserName(currentUser(authentication).getUsername()).stream()
                    .map(Manager::getId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }
        if (hasRole(authentication, "MANAGER")) {
            return userService.findManagerIdsByUserId(currentUser(authentication).getId());
        }
        return List.of();
    }

    private boolean isVisibleInCurrentManagerControl(Long incidentId, Authentication authentication) {
        String username = authentication == null ? null : authentication.getName();
        if (incidentId == null || username == null || username.isBlank()) {
            return false;
        }
        return managerControlConcreteItemRepository.existsByEntityTypeAndEntityIdAndControl_Manager_User_Username(
                "RISK",
                incidentId,
                username
        );
    }

    private User workerFor(WorkerRiskIncident incident) {
        if (incident == null || incident.getWorkerUserId() == null) {
            return null;
        }
        try {
            User worker = userService.findByIdToUserInfo(incident.getWorkerUserId());
            return worker != null && Objects.equals(worker.getId(), incident.getWorkerUserId()) ? worker : null;
        } catch (java.util.NoSuchElementException exception) {
            return null;
        }
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

    private void clearSlaDeliveryClaim(WorkerRiskIncident incident) {
        incident.setSlaDeliveryClaimToken(null);
        incident.setSlaDeliveryClaimedAt(null);
        incident.setSlaDeliveryClaimKind(null);
    }
}
