package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.util.LinkedHashSet;
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

    @Transactional(readOnly = true)
    public String refresh(String context) {
        String source = context == null ? "" : context;
        Set<Long> ids = sourceIds(source);
        if (ids.isEmpty()) return source;
        StringBuilder result = new StringBuilder(source)
                .append("\n\nТЕКУЩЕЕ СОСТОЯНИЕ И РЕЗУЛЬТАТ СВЯЗАННЫХ ЗАДАЧ. ")
                .append("Этот блок новее снимка отчёта и имеет приоритет:\n");
        for (ManagerDailyControlConcreteItem item : concreteItemRepository.findAllById(ids)) {
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
        return result.toString().trim();
    }

    @Transactional(readOnly = true)
    public boolean resolvedSatisfactorily(Long sourceTaskId) {
        if (sourceTaskId == null || sourceTaskId <= 0) return false;
        return concreteItemRepository.findById(sourceTaskId)
                .map(this::resolvedSatisfactorily)
                .orElse(false);
    }

    private boolean resolvedSatisfactorily(ManagerDailyControlConcreteItem source) {
        if (source.getStatus() == ManagerDailyControlItemStatus.OPEN) return false;
        return clientChatItem(source)
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
