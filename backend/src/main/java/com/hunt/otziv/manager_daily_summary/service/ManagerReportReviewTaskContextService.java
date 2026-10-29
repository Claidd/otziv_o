package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewTaskContextService {

    private static final Pattern SOURCE_TASK_ID = Pattern.compile("sourceTaskId=(\\d+)");
    private static final Set<String> CLIENT_CHAT_TYPES = Set.of(
            "CLIENT_CHAT_UNANSWERED",
            "CLIENT_CHAT_AUDIT"
    );

    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ManagerProblemDiagnosticsService diagnosticsService;

    /** Read only the question's sources; never append the complete historical report to fresh evidence. */
    @Transactional
    public String refreshSources(String historical, Collection<Long> requestedIds, Long managerId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (requestedIds != null) requestedIds.stream().filter(java.util.Objects::nonNull)
                .filter(id -> id > 0).forEach(ids::add);
        if (ids.isEmpty()) ids.addAll(sourceIds(historical == null ? "" : historical));
        if (ids.isEmpty()) return "Актуальные источники задач не привязаны; их состояние не подтверждено.";
        return refresh(ids.stream().map(id -> "sourceTaskId=" + id)
                .collect(java.util.stream.Collectors.joining("\n")), managerId);
    }

    @Transactional
    public String refresh(String context, Long managerId) {
        String source = context == null ? "" : context.replace("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:", "Ранее сохранённый контекст:");
        Set<Long> ids = sourceIds(source);
        if (ids.isEmpty()) return source;
        StringBuilder result = new StringBuilder(source)
                .append("\n\nТЕКУЩЕЕ СОСТОЯНИЕ И РЕЗУЛЬТАТ СВЯЗАННЫХ ЗАДАЧ. ")
                .append("Этот блок новее снимка отчёта и имеет приоритет:\n");
        Set<Long> found = new LinkedHashSet<>();
        for (ManagerDailyControlConcreteItem item : concreteItemRepository.findAllById(ids)) {
            if (managerId == null || item.getControl() == null || !managerId.equals(item.getControl().managerId())) continue;
            found.add(item.getId());
            result.append("- sourceTaskId=").append(item.getId())
                    .append("; currentStatus=").append(item.getStatus())
                    .append("; title=").append(clean(item.getTitle()));
            clientChatItem(item).ifPresent(chat -> result
                    .append("; clientMessage=").append(clean(chat.getLastMessageText()))
                    .append("; managerReply=").append(clean(chat.getResolutionReplyText()))
                    .append("; resolutionType=").append(chat.getResolutionType())
                    .append("; replyQuality=").append(chat.getReplyQuality())
                    .append("; replyQualityReason=").append(clean(chat.getReplyQualityReason()))
                    .append("; auditRequired=").append(chat.isAuditRequired()));
            result.append('\n');
        }
        ids.stream().filter(id -> !found.contains(id)).forEach(id -> result
                .append("- sourceTaskId=").append(id)
                .append("; evidenceStatus=UNAVAILABLE; источник отсутствует, выполнение не подтверждено\n"));
        result.append('\n').append(diagnosticsService.contextForAudit(found));
        return result.toString().trim();
    }

    @Transactional(readOnly = true)
    public boolean resolvedSatisfactorily(Long sourceTaskId) {
        if (sourceTaskId == null || sourceTaskId <= 0) return false;
        return concreteItemRepository.findById(sourceTaskId)
                .map(this::resolvedSatisfactorily)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean systemOwnsSource(Long sourceTaskId, Long managerId) {
        if (sourceTaskId == null || managerId == null) return false;
        return concreteItemRepository.findById(sourceTaskId)
                .filter(source -> source.getControl() != null && managerId.equals(source.getControl().managerId()))
                .filter(source -> "AUTOMATION_FAILURE".equals(source.getEntityType()))
                .map(source -> diagnosticsService.systemOwnsDeliveryReconciliation(sourceTaskId))
                .orElse(false);
    }

    private boolean resolvedSatisfactorily(ManagerDailyControlConcreteItem source) {
        if (source.getStatus() != ManagerDailyControlItemStatus.RESOLVED) {
            boolean resolvedLater = source.getDeferredAt() != null
                    && source.getControl() != null
                    && source.getControl().managerId() != null
                    && concreteItemRepository.existsResolutionAfterDeferral(
                            source.getControl().managerId(),
                            source.getEntityKey(),
                            source.getDeferredAt(),
                            ManagerDailyControlItemStatus.RESOLVED);
            if (!resolvedLater) return false;
        }
        var chatSource = clientChatItem(source);
        if (CLIENT_CHAT_TYPES.contains(clean(source.getEntityType())) && chatSource.isEmpty()) return false;
        if (clean(source.getEntityType()).startsWith("COMMON_INVOICE") || "AUTOMATION_FAILURE".equals(source.getEntityType())) {
            return diagnosticsService.resolutionState(source.getId()) == ManagerProblemDiagnosticsService.ResolutionState.RESOLVED;
        }
        return chatSource
                .map(item -> item.getStatus() != ClientChatUnansweredStatus.OPEN
                        && !item.isAuditRequired()
                        && item.getReplyQuality() != ClientChatReplyQuality.PARTIAL
                        && item.getReplyQuality() != ClientChatReplyQuality.SUSPICIOUS)
                .orElse(true);
    }

    private java.util.Optional<ClientChatUnansweredItem> clientChatItem(
            ManagerDailyControlConcreteItem source
    ) {
        if (source == null
                || source.getEntityId() == null
                || !CLIENT_CHAT_TYPES.contains(clean(source.getEntityType()))) {
            return java.util.Optional.empty();
        }
        return unansweredRepository.findById(source.getEntityId());
    }

    private Set<Long> sourceIds(String context) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher matcher = SOURCE_TASK_ID.matcher(context);
        while (matcher.find()) {
            try {
                long id = Long.parseLong(matcher.group(1));
                if (id > 0) ids.add(id);
            } catch (NumberFormatException ignored) {
                // A malformed service marker is ignored; the human report remains usable.
            }
        }
        return ids;
    }

    private String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 1000 ? cleaned : cleaned.substring(0, 999) + "…";
    }
}
