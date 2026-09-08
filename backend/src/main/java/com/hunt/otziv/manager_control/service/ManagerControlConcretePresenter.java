package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the existing concrete-card DTO, including risk labels and SLA, inside the read/write transaction. */
@Service
@RequiredArgsConstructor
public class ManagerControlConcretePresenter {
    private final WorkerRiskIncidentRepository riskIncidentRepository;
    private final ManagerControlWorkerTaskLookup workerTaskLookup;
    private final ManagerControlInvoiceDiagnostics invoiceDiagnostics;
    private final ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;
    private final CompanyRepository companyRepository;
    private final ManagerControlSlaPolicy slaPolicy;

    ManagerControlConcreteItemResponse concreteItemResponse(ManagerDailyControlConcreteItem item) {
        return concreteItemResponse(item, null);
    }

    ManagerControlConcreteItemResponse concreteItemResponse(ManagerDailyControlConcreteItem item, String contactText) {
        return concreteItemResponse(item, contactText, null);
    }

    ManagerControlConcreteItemResponse concreteItemResponse(
            ManagerDailyControlConcreteItem item,
            String contactText,
            String specialistNameOverride
    ) {
        if (workerTaskLookup.isWorkerRiskConcrete(item)) {
            return riskConcreteItemResponse(item, contactText, specialistNameOverride);
        }
        String specialistName = safe(specialistNameOverride).isBlank()
                ? specialistNameForConcreteItem(item)
                : specialistNameOverride;
        String targetUrl = item.getTargetUrl();
        if (isChatBindingIssueConcrete(item)) {
            targetUrl = companyBoardUrlByKeyword(item.getTitle(), targetUrl);
        }
        ManagerControlConcreteItemResponse response = new ManagerControlConcreteItemResponse(
                item.getId(),
                item.getEntityType(),
                item.getEntityId(),
                item.getTitle(),
                item.getSubtitle(),
                item.getStatusLabel(),
                item.getAgeDays(),
                item.getReason(),
                targetUrl,
                item.getOrderDetailsId(),
                item.getChatUrl(),
                item.getFollowUpAt(),
                item.getLastManualTouchAt(),
                item.getStatus().name(),
                item.getActionType() == null ? null : item.getActionType().name(),
                item.getComment(),
                item.getUpdatedAt(),
                item.getResolvedAt(),
                item.getWorkerNotificationAttemptedAt(),
                item.getWorkerNotificationSentAt(),
                item.getWorkerNotificationAcceptedAt(),
                item.getWorkerNotificationAcceptedByUserId(),
                item.getWorkerNotificationFailureReason(),
                contactText,
                null,
                item.getWorkerExplanation(),
                item.getWorkerExplanationAt(),
                null,
                null,
                null,
                null,
                specialistName,
                null,
                null,
                null,
                null
        );
        return slaPolicy.decorateConcreteSla(item.getParentItem(), response.withSla(item.getCreatedAt(), null, null, null));
    }

    String companyBoardUrlByKeyword(String keyword, String fallbackUrl) {
        String normalizedKeyword = safe(keyword);
        if (normalizedKeyword.isBlank() || normalizedKeyword.matches("\\d+")) {
            return fallbackUrl;
        }
        List<String> params = new ArrayList<>();
        params.add("section=companies");
        params.add("status=" + encode("Все"));
        params.add("pageNumber=0");
        params.add("pageSize=10");
        params.add("sortDirection=desc");
        params.add("keyword=" + encode(normalizedKeyword));
        return "/companies?" + String.join("&", params);
    }

    ManagerControlConcreteItemResponse riskConcreteItemResponse(
            ManagerDailyControlConcreteItem item,
            String contactText,
            String specialistNameOverride
    ) {
        WorkerRiskIncident incident = item.getEntityId() == null
                ? null
                : riskIncidentRepository.findById(item.getEntityId()).orElse(null);
        String specialistName = safe(specialistNameOverride).isBlank()
                ? firstNonBlank(
                incident == null ? null : incident.getWorkerName(),
                incident == null ? null : incident.getWorkerUsername(),
                specialistNameForConcreteItem(item)
        )
                : specialistNameOverride;
        String riskResolutionAction = incident == null || incident.getResolutionAction() == null
                ? null
                : incident.getResolutionAction().name();
        String workerExplanation = firstNonBlank(
                incident == null ? null : incident.getWorkerExplanation(),
                item.getWorkerExplanation()
        );
        LocalDateTime workerExplanationAt = incident == null || incident.getWorkerExplanationAt() == null
                ? item.getWorkerExplanationAt()
                : incident.getWorkerExplanationAt();
        boolean riskExplanationRequested = incident != null
                && (incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED
                || incident.getExplanationRequestedAt() != null
                || incident.getExplanationPromptedAt() != null
                || incident.getWorkerExplanationAt() != null);
        LocalDateTime workerNotificationAttemptedAt = firstNonNullTime(
                item.getWorkerNotificationAttemptedAt(),
                riskExplanationRequested ? incident.getExplanationRequestedAt() : null,
                riskExplanationRequested ? incident.getCreatedAt() : null
        );
        LocalDateTime workerNotificationSentAt = firstNonNullTime(
                item.getWorkerNotificationSentAt(),
                riskExplanationRequested ? incident.getExplanationRequestedAt() : null,
                riskExplanationRequested ? incident.getCreatedAt() : null
        );
        LocalDateTime workerNotificationAcceptedAt = firstNonNullTime(
                item.getWorkerNotificationAcceptedAt(),
                incident == null ? null : incident.getExplanationAcceptedAt()
        );
        Long workerNotificationAcceptedByUserId = firstNonNullLong(
                item.getWorkerNotificationAcceptedByUserId(),
                workerNotificationAcceptedAt == null || incident == null ? null : incident.getWorkerUserId()
        );
        String workerNotificationFailureReason = workerNotificationSentAt != null
                || workerNotificationAcceptedAt != null
                || workerExplanationAt != null
                ? null
                : item.getWorkerNotificationFailureReason();
        ManagerControlConcreteItemResponse response = new ManagerControlConcreteItemResponse(
                item.getId(),
                item.getEntityType(),
                item.getEntityId(),
                item.getTitle(),
                item.getSubtitle(),
                item.getStatusLabel(),
                item.getAgeDays(),
                item.getReason(),
                item.getTargetUrl(),
                item.getOrderDetailsId(),
                item.getChatUrl(),
                item.getFollowUpAt(),
                item.getLastManualTouchAt(),
                item.getStatus().name(),
                item.getActionType() == null ? null : item.getActionType().name(),
                item.getComment(),
                item.getUpdatedAt(),
                item.getResolvedAt(),
                workerNotificationAttemptedAt,
                workerNotificationSentAt,
                workerNotificationAcceptedAt,
                workerNotificationAcceptedByUserId,
                workerNotificationFailureReason,
                contactText,
                riskResolutionAction,
                workerExplanation,
                workerExplanationAt,
                incident == null ? null : incident.getPenaltyPoints(),
                incident == null || incident.getRollbackStatus() == null ? null : incident.getRollbackStatus().name(),
                incident == null ? null : incident.getRollbackMessage(),
                incident == null ? null : canRollbackRiskIncident(incident),
                specialistName,
                null,
                null,
                null,
                null
        );
        return slaPolicy.decorateConcreteSla(item.getParentItem(), response.withSla(item.getCreatedAt(), null, null, null));
    }

    String companySpecialistName(Company company) {
        if (company == null || company.getWorkers() == null) return "";
        List<String> names = company.getWorkers().stream()
                .map(Worker::getUser)
                .map(workerTaskLookup::userDisplayName)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
        if (names.size() == 1) return names.getFirst();
        if (names.size() > 1) return String.join(", ", names);
        return "";
    }

    boolean isChatBindingIssueConcrete(ManagerDailyControlConcreteItem item) {
        if (item == null) {
            return false;
        }
        ManagerDailyControlItem parent = item.getParentItem();
        if (parent != null && "CHAT_BINDING_ISSUES".equals(parent.getReasonCode())) {
            return true;
        }
        String reason = safe(item.getReason()).toLowerCase(Locale.ROOT);
        return reason.contains("группа из ссылки") && reason.contains("не привязан");
    }

    String specialistNameForConcreteItem(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null) {
            return "";
        }
        if ("COMMON_INVOICE".equals(safe(concreteItem.getEntityType()))) {
            return invoiceDiagnostics.specialistName(concreteItem.getEntityId());
        }
        if (ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(safe(concreteItem.getEntityType()))) {
            ScheduledClientMessageState state = scheduledClientMessageStateRepository.findById(concreteItem.getEntityId()).orElse(null);
            Company company = state == null || state.getCompanyId() == null
                    ? null
                    : companyRepository.findByIdWithWorkers(state.getCompanyId()).orElse(null);
            String names = companySpecialistName(company);
            if (!names.isBlank()) return names;
        }
        return workerTaskLookup.userDisplayName(workerTaskLookup.workerUserForTask(concreteItem));
    }

    boolean canRollbackRiskIncident(WorkerRiskIncident incident) {
        if (incident == null
                || incident.getStatus() != WorkerRiskIncidentStatus.VIOLATION
                || incident.getRollbackStatus() != null) {
            return false;
        }
        return "BAD_TASK_COMPLETE".equals(incident.getAction())
                || "RECOVERY_TASK_COMPLETE".equals(incident.getAction());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = safe(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private LocalDateTime firstNonNullTime(LocalDateTime... values) {
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Long firstNonNullLong(Long... values) {
        if (values == null) {
            return null;
        }
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
