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
import java.time.LocalDate;
import java.util.Locale;
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

    /**
     * Closure reason of a client-chat item the manager answered in another messenger.
     * The owner checks it; it is not a manager failure for the audit.
     */
    static final String ANSWERED_OUTSIDE_CHAT = "ANSWERED_OUTSIDE_CHAT";

    private final ManagerDailyControlConcreteItemRepository concreteItemRepository;
    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ManagerProblemDiagnosticsService diagnosticsService;
    private final com.hunt.otziv.manager_control.service.ManagerControlInvoiceDiagnostics invoiceDiagnostics;
    private final ManagerAuditDeferralFacts deferralFacts;
    private final ManagerCommunicationDailyReportSectionService conversationExchange;

    static boolean answeredOutsideChat(ClientChatUnansweredItem item) {
        return item != null && item.getStatus() != ClientChatUnansweredStatus.OPEN
                && (ANSWERED_OUTSIDE_CHAT.equals(clean(item.getResolutionReasonCode()).toUpperCase(Locale.ROOT))
                    || ANSWERED_OUTSIDE_CHAT.equals(clean(item.getCloseReason()).toUpperCase(Locale.ROOT)));
    }

    /**
     * A deferred card is the manager's remark only when the manager deferred it and the
     * problem is still on the manager's board. An old copy whose problem left the board, or
     * a deferral made by someone else, is not asked about.
     */
    /**
     * Unanswered-message cards that are not an audit remark at {@code at}: the client got a
     * staff reply (or the case was otherwise closed) after the evening control snapshot, or
     * the client's message is still younger than the audit grace. Returns the card ids.
     */
    @Transactional(readOnly = true)
    public Set<Long> unansweredNotYetARemark(Collection<ManagerDailyControlConcreteItem> cards,
                                             java.time.LocalDateTime at, int graceMinutes) {
        if (cards == null || cards.isEmpty() || at == null) return Set.of();
        java.util.Map<Long, Long> cardByChat = new java.util.HashMap<>();
        for (ManagerDailyControlConcreteItem card : cards) {
            if (card != null && card.getId() != null && card.getEntityId() != null
                    && "CLIENT_CHAT_UNANSWERED".equals(clean(card.getEntityType()))) {
                cardByChat.put(card.getEntityId(), card.getId());
            }
        }
        if (cardByChat.isEmpty()) return Set.of();
        java.time.LocalDateTime youngerThan = at.minusMinutes(Math.max(0, graceMinutes));
        Set<Long> result = new LinkedHashSet<>();
        for (ClientChatUnansweredItem chat : unansweredRepository.findAllById(cardByChat.keySet())) {
            if (chat == null || chat.getId() == null) continue;
            boolean repliedSinceSnapshot = chat.getStatus() != ClientChatUnansweredStatus.OPEN;
            boolean withinGrace = chat.getLastClientMessageAt() != null
                    && chat.getLastClientMessageAt().isAfter(youngerThan);
            if (repliedSinceSnapshot || withinGrace) result.add(cardByChat.get(chat.getId()));
        }
        return Set.copyOf(result);
    }

    @Transactional(readOnly = true)
    public boolean deferralAttributable(ManagerDailyControlConcreteItem item, LocalDate reportDate) {
        if (item == null || item.getControl() == null || item.getControl().managerId() == null || reportDate == null) {
            return false;
        }
        Long parentItemId = item.getParentItem() == null ? null : item.getParentItem().getId();
        // Common invoices are not re-created daily; their current eligibility is decided by the
        // live common-invoice rule in resolvedSatisfactorily. Other problems are re-created daily.
        boolean invoice = clean(item.getEntityType()).startsWith("COMMON_INVOICE");
        return deferralFacts.deferredByControlManager(parentItemId, item.getTitle())
                && (invoice || deferralFacts.stillOnBoard(item.getControl().managerId(), item.getEntityKey(),
                        reportDate));
    }

    /** Older conversational questions had no daily-control card. Bind only one exact,
     * manager-owned case from the report day, using both company and quoted message. */
    @Transactional(readOnly = true)
    public ManagerReportReviewQualityService.ReviewQuestion bindConversationSource(
            ManagerReportReviewQualityService.ReviewQuestion question, Long managerId, LocalDate date) {
        if (question == null || managerId == null || date == null || !question.sourceTaskIds().isEmpty()
                || !question.sourceChatItemIds().isEmpty() || question.question().startsWith("Специалист ")) return question;
        String text = normalized(question.question());
        var candidates = unansweredRepository.findDailyReportItems(managerId, date.atStartOfDay(),
                date.plusDays(1).atStartOfDay(), ClientChatUnansweredStatus.OPEN);
        if (candidates == null) return question;
        var matches = candidates.stream().filter(item -> item.getId() != null
                        && managerId.equals(item.managerId()) && item.getClosedAt() != null
                        && date.equals(item.getClosedAt().toLocalDate()))
                .filter(item -> {
                    String company = normalized(item.companyTitle());
                    String message = normalized(item.getLastMessageText());
                    return company.length() >= 3 && text.contains(company) && message.length() >= 30
                            && text.contains(message.substring(0, Math.min(100, message.length())));
                }).map(ClientChatUnansweredItem::getId).distinct().toList();
        return matches.size() == 1 ? new ManagerReportReviewQualityService.ReviewQuestion(question.question(),
                question.expectedPoints(), question.sourceTaskIds(), matches) : question;
    }

    /** Recorded conversation proves its words, never payment or completion of the order. */
    @Transactional(readOnly = true)
    public String conversationEvidence(Collection<Long> ids, Long managerId) {
        StringBuilder result = new StringBuilder("СЕРВЕРНЫЕ ФАКТЫ ДЛЯ ПРОВЕРКИ:\n");
        if (ids == null) return result.toString();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Long id : ids.stream().filter(java.util.Objects::nonNull).filter(value -> value > 0).distinct().toList()) {
            var item = id == null || managerId == null ? null : unansweredRepository.findById(id)
                    .filter(row -> managerId.equals(row.managerId())).orElse(null);
            java.util.Map<String,String> fields = new java.util.LinkedHashMap<>();
            if (item == null) {
                fields.put("availability", "unavailable");
            } else {
                fields.put("client_message", java.util.Objects.requireNonNullElse(item.getLastMessageText(), ""));
                fields.put("manager_reply", java.util.Objects.requireNonNullElse(item.getResolutionReplyText(), ""));
                fields.put("client_message_at", String.valueOf(item.getLastClientMessageAt()));
                fields.put("reply_recorded_at", String.valueOf(item.getClosedAt()));
                if (answeredOutsideChat(item)) fields.put("answered_outside_chat",
                        "Менеджер ответил клиенту в другом мессенджере; ответ проверяет владелец");
                // One reply can cover several client messages, an earlier reply can already answer
                // the question, and a reply can continue in the next message: show the whole exchange.
                int position = 0;
                for (var message : conversationExchange.exchange(item)) {
                    fields.put("exchange_" + (++position), message.describe());
                }
            }
            for (var field : fields.entrySet()) {
                try {
                    result.append(json.writeValueAsString(java.util.Map.of("id", "chat-case:" + id + ":" + field.getKey(),
                            "source", "recorded_chat_message", "sourceChatItemId", id,
                            "field", field.getKey(), "value", field.getValue(),
                            "observedAt", java.time.Instant.now().toString()))).append('\n');
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new IllegalStateException("Cannot encode recorded conversation", failure);
                }
            }
            if(item!=null) result.append(java.util.Objects.requireNonNullElse(
                    diagnosticsService.contextForConversationPayments(id,managerId),""));
        }
        return result.toString();
    }

    private String normalized(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    public String workerGoalTaskEvidence(Long workerId, Long managerId, LocalDate date) {
        return diagnosticsService.contextForWorkerGoal(workerId,managerId,date);
    }

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
            clientChatItem(item).ifPresent(chat -> {
                boolean outside = answeredOutsideChat(chat);
                result.append("; clientMessage=").append(clean(chat.getLastMessageText()))
                        .append("; managerReply=").append(clean(chat.getResolutionReplyText()))
                        .append("; resolutionType=").append(chat.getResolutionType())
                        .append("; replyQuality=").append(outside ? "NOT_APPLICABLE" : chat.getReplyQuality())
                        .append("; replyQualityReason=").append(outside
                                ? "ответ дан в другом мессенджере; проверяет владелец, это не ошибка менеджера"
                                : clean(chat.getReplyQualityReason()))
                        .append("; auditRequired=").append(!outside && chat.isAuditRequired());
                String exchange = conversationExchange.exchange(chat).stream()
                        .map(ManagerCommunicationDailyReportSectionService.ExchangeMessage::describe)
                        .map(line -> line.length() <= 220 ? line : line.substring(0, 219) + "…")
                        .collect(java.util.stream.Collectors.joining(" | "));
                if (!exchange.isBlank()) {
                    result.append("; exchange=").append(exchange)
                            .append("; exchangeNote=оценка ответа по одной паре сообщений неполна: ")
                            .append("учитывай всю серию клиента и все ответы сотрудников в exchange");
                }
            });
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
        // A deferred daily copy can outlive the original problem for weeks. Use
        // the same current eligibility rule as the live common-invoice board.
        if (clean(source.getEntityType()).startsWith("COMMON_INVOICE")) {
            return invoiceDiagnostics.resolvedForAudit(source.getEntityId());
        }
        // The manager answered in another messenger; the owner checks that separately.
        if (clientChatItem(source).filter(ManagerReportReviewTaskContextService::answeredOutsideChat).isPresent()) {
            return true;
        }
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
        if ("AUTOMATION_FAILURE".equals(source.getEntityType())) {
            return diagnosticsService.resolutionState(source.getId()) == ManagerProblemDiagnosticsService.ResolutionState.RESOLVED;
        }
        return chatSource
                .map(item -> answeredOutsideChat(item)
                        || item.getStatus() != ClientChatUnansweredStatus.OPEN
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

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 1000 ? cleaned : cleaned.substring(0, 999) + "…";
    }
}
