package com.hunt.otziv.manager_control.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.Fact;

/** Explicit allowlist of database fields: no payment links, credentials, delivery envelopes or full chat exports. */
@Service
@RequiredArgsConstructor
public class ManagerProblemEvidenceCollector {
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Evidence collect(Long sourceTaskId) {
        LocalDateTime observedAt = LocalDateTime.now();
        List<Map<String, Object>> sources = jdbc.queryForList("""
                SELECT i.control_entity_id, c.manager_id, c.control_date, i.entity_key, i.entity_type, i.entity_id,
                       i.title, i.reason, i.item_status, i.comment, i.follow_up_at,
                       i.last_manual_touch_at, i.resolved_at, i.updated_at, i.resolved_episode_count
                FROM manager_daily_control_concrete_items i
                JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                WHERE i.control_entity_id=?
                """, sourceTaskId);
        if (sources.isEmpty()) return null;
        Map<String, Object> requested = sources.getFirst();
        long managerId = number(requested, "manager_id");
        String type = string(requested, "entity_type");
        Long entityId = requested.get("entity_id") == null ? null : number(requested, "entity_id");
        // Daily copies of a card describe the same entity. Never let an older card roll back its current state.
        boolean invoice = type.startsWith("COMMON_INVOICE");
        String family = invoice ? "COMMON_INVOICE" : type.startsWith("CLIENT_CHAT") ? "CLIENT_CHAT" : type;
        List<Map<String, Object>> latest = entityId == null ? List.of() : jdbc.queryForList("""
                SELECT i.control_entity_id, c.manager_id, c.control_date, i.entity_key, i.entity_type, i.entity_id,
                       i.title, i.reason, i.item_status, i.comment, i.follow_up_at,
                       i.last_manual_touch_at, i.resolved_at, i.updated_at, i.resolved_episode_count
                FROM manager_daily_control_concrete_items i
                JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                WHERE c.manager_id=? AND i.entity_id=? AND (i.entity_type=? OR (?='COMMON_INVOICE' AND i.entity_type LIKE 'COMMON_INVOICE%')
                    OR (?='CLIENT_CHAT' AND i.entity_type IN ('CLIENT_CHAT_UNANSWERED','CLIENT_CHAT_AUDIT')))
                ORDER BY i.control_entity_id DESC LIMIT 1
                """, managerId, entityId, type, family, family);
        Map<String, Object> source = latest.isEmpty() ? requested : latest.getFirst();
        long currentSourceId = number(source, "control_entity_id");
        List<Fact> facts = new ArrayList<>();
        addRow(facts, "card:" + currentSourceId, "card", source,
                "control_date", "title", "entity_type", "entity_id", "item_status", "reason", "comment", "follow_up_at", "last_manual_touch_at", "resolved_at");
        List<Long> stateIds = new ArrayList<>();
        if (invoice && entityId != null) {
            List<Map<String, Object>> invoices = jdbc.queryForList("""
                    SELECT v.invoice_id, v.status, v.amount_kopecks, v.paid_kopecks, v.last_error,
                           v.sent_at, v.next_reminder_at, v.payment_success_notification_error, v.updated_at, a.enabled AS account_enabled
                    FROM common_invoices v JOIN common_billing_accounts a ON a.account_id=v.account_id
                    LEFT JOIN companies company ON company.company_id=a.invoice_company_id
                    WHERE v.invoice_id=? AND (a.manager_id=? OR company.company_manager=? OR EXISTS (
                        SELECT 1 FROM common_invoice_orders io JOIN orders o ON o.order_id=io.order_id
                        WHERE io.invoice_id=v.invoice_id AND o.order_manager=?))
                    """, entityId, managerId, managerId, managerId);
            if (invoices.isEmpty()) {
                facts.add(new Fact("invoice:" + entityId + ":missing", "invoice", "availability", "неизвестно: счет отсутствует или больше не относится к менеджеру"));
                return new Evidence(managerId + ":" + family + ":" + entityId, managerId, currentSourceId, false, List.copyOf(facts), observedAt);
            }
            invoices.forEach(row -> addRow(facts, "invoice:" + entityId, "invoice", row,
                    "invoice_id", "status", "amount_kopecks", "paid_kopecks", "last_error", "sent_at", "next_reminder_at", "payment_success_notification_error", "account_enabled"));
            List<Map<String, Object>> orders = jdbc.queryForList("""
                    SELECT io.order_id, io.ready, io.paid, io.amount_kopecks, io.active_membership, io.publication_blocker_since, io.paid_at, io.updated_at,
                           s.order_status_title AS current_order_status
                    FROM common_invoice_orders io LEFT JOIN orders o ON o.order_id=io.order_id
                    LEFT JOIN order_statuses s ON s.order_status_id=o.order_status
                    WHERE io.invoice_id=? ORDER BY io.order_id LIMIT 101
                    """, entityId);
            orders.stream().limit(100).forEach(row -> addRow(facts, "order:" + number(row, "order_id"), "invoice_order", row,
                    "order_id", "ready", "paid", "amount_kopecks", "active_membership", "publication_blocker_since", "paid_at", "current_order_status"));
            Map<String, Object> counts = jdbc.queryForMap("""
                    SELECT COUNT(*) AS orderCount, COALESCE(SUM(io.ready),0) AS readyCount,
                           COALESCE(SUM(s.order_status_title='Архив'),0) AS archivedActiveCount
                    FROM common_invoice_orders io LEFT JOIN orders o ON o.order_id=io.order_id
                    LEFT JOIN order_statuses s ON s.order_status_id=o.order_status
                    WHERE io.invoice_id=? AND io.active_membership=TRUE
                    """, entityId);
            facts.add(new Fact("invoice:" + entityId + ":orderCount", "invoice", "orderCount", counts.get("orderCount").toString()));
            facts.add(new Fact("invoice:" + entityId + ":readyCount", "invoice", "readyCount", counts.get("readyCount").toString()));
            facts.add(new Fact("invoice:" + entityId + ":archivedActiveCount", "invoice", "archivedActiveCount", counts.get("archivedActiveCount").toString()));
            if (orders.size() <= 100) {
                String ids = orders.stream().filter(row -> Boolean.TRUE.equals(row.get("active_membership")) || "1".equals(String.valueOf(row.get("active_membership"))))
                        .map(row -> Long.toString(number(row, "order_id"))).collect(java.util.stream.Collectors.joining(","));
                facts.add(new Fact("invoice:" + entityId + ":orderIds", "invoice", "orderIds", ids));
            } else facts.add(new Fact("invoice:" + entityId + ":coverage", "coverage", "orderIds", "Список позиций ограничен первыми 100; полный состав требует проверки"));
            stateIds.addAll(jdbc.queryForList("""
                    SELECT DISTINCT s.state_id FROM scheduled_client_message_state s
                    JOIN common_invoice_orders o ON o.order_id=s.order_id
                    WHERE o.invoice_id=? AND o.active_membership=TRUE ORDER BY s.state_id LIMIT 41
                    """, Long.class, entityId));
            if (stateIds.size() > 40) {
                stateIds = new ArrayList<>(stateIds.subList(0, 40));
                facts.add(new Fact("invoice:" + entityId + ":automation_coverage", "coverage", "automation_states_truncated", "Показаны только 40 состояний автоматизации; наличие других ошибок не исключено"));
            }
        } else if ("ORDER".equals(type) && entityId != null) {
            stateIds.addAll(collectOrder(facts, entityId, managerId));
        } else if ("AUTOMATION_FAILURE".equals(type) && entityId != null) {
            List<Long> ownedState = jdbc.queryForList("""
                    SELECT s.state_id FROM scheduled_client_message_state s
                    LEFT JOIN orders o ON o.order_id=s.order_id
                    LEFT JOIN companies c ON c.company_id=COALESCE(o.order_company,s.company_id)
                    WHERE s.state_id=? AND COALESCE(o.order_manager,c.company_manager)=?
                    """, Long.class, entityId, managerId);
            if (ownedState.isEmpty()) facts.add(new Fact("automation:" + entityId + ":availability", "coverage", "availability", "Автоматизация отсутствует или больше не относится к менеджеру"));
            stateIds.addAll(ownedState);
        } else if (("CLIENT_CHAT_UNANSWERED".equals(type) || "CLIENT_CHAT_AUDIT".equals(type)) && entityId != null) {
            List<Map<String, Object>> chats = jdbc.queryForList("""
                    SELECT id, status, last_client_message_id, last_client_message_at, first_opened_at,
                           closed_at, resolution_type, resolution_message_id, resolution_reason_code,
                           resolution_reply_text, last_message_text, resolved_by_user_id, manual_override, reply_quality, audit_required
                    FROM client_chat_unanswered_items WHERE id=? AND manager_id=?
                    """, entityId, managerId);
            if (chats.isEmpty()) facts.add(new Fact("chat:" + entityId + ":availability", "coverage", "availability", "Исходная переписка недоступна"));
            for (Map<String, Object> chat : chats) {
                addRow(facts, "chat:" + entityId, "client_chat", chat,
                        "id", "status", "last_client_message_id", "last_client_message_at", "first_opened_at", "closed_at", "resolution_type", "resolution_message_id",
                        "resolution_reason_code", "resolved_by_user_id", "manual_override", "reply_quality", "audit_required");
                // A direct recorded quote is evidence of what was said, never proof of the claims inside it.
                if (chat.get("last_message_text") != null) addRecordedText(facts, "chat:" + entityId + ":client_text", "recorded_client_text", chat.get("last_message_text"));
                if (chat.get("resolution_reply_text") != null) addRecordedText(facts, "chat:" + entityId + ":reply_text", "recorded_reply_text", chat.get("resolution_reply_text"));
                for (String idField : List.of("last_client_message_id", "resolution_message_id")) {
                    if (chat.get(idField) == null) continue;
                    long messageId = number(chat, idField);
                    jdbc.queryForList("""
                            SELECT id, platform, direction, sender_role, message_text, message_at, routing_ambiguous
                            FROM client_chat_messages WHERE id=? AND (manager_id=? OR manager_id IS NULL)
                            """, messageId, managerId).forEach(message -> {
                        addRow(facts, "message:" + messageId, "client_chat_message", message, "id", "platform", "direction", "sender_role", "message_at", "routing_ambiguous");
                        addRecordedText(facts, "message:" + messageId + ":recorded_text", "recorded_text", message.get("message_text"));
                    });
                }
            }
        } else {
            facts.add(new Fact("source:coverage", "coverage", "live_context", "Контекст источника ограничен карточкой; причину и выполнение действий необходимо подтвердить дополнительно"));
        }
        // Poll/check timestamps alone do not establish a new delivery result. Keep them in source logs,
        // not model evidence; actual delivery status, errors, attempts and success times remain versioned.
        for (Long stateId : stateIds) {
            jdbc.queryForList("""
                    SELECT s.state_id, s.scenario, s.order_id, s.state_status, s.last_error_code, s.last_error_message,
                           s.consecutive_failures, s.next_attempt_at, s.last_attempt_at, s.last_success_at,
                           s.delivery_status, s.target_key,
                           COALESCE(o.order_status_changed_at,TIMESTAMP(o.order_changed),TIMESTAMP(o.order_created)) AS cycle_started_at,
                           COALESCE(o.order_waiting_for_client_changed_at,o.order_status_changed_at,
                               TIMESTAMP(o.order_changed),TIMESTAMP(o.order_created)) AS waiting_cycle_started_at
                    FROM scheduled_client_message_state s LEFT JOIN orders o ON o.order_id=s.order_id WHERE s.state_id=?
                    """, stateId).forEach(row -> {
                addRow(facts, "automation:" + stateId, "automation", row,
                    "state_id", "scenario", "order_id", "state_status", "last_error_code", "last_error_message",
                    "consecutive_failures", "next_attempt_at", "last_attempt_at", "last_success_at", "delivery_status");
                LocalDateTime cycle = timestamp(row.get("cycle_started_at")), waiting = timestamp(row.get("waiting_cycle_started_at"));
                String target = string(row, "target_key"), id = string(row, "order_id");
                boolean orderTarget = target.startsWith("order:" + id + ":") || target.startsWith("payment-return:" + id + ":") || target.startsWith("client-text:" + id + ":");
                String current = cycle == null || waiting == null || !orderTarget ? "неизвестно"
                        : Boolean.toString(List.of("order:" + id + ":" + cycle.withNano(0),
                                "payment-return:" + id + ":" + cycle.withNano(0), "client-text:" + id + ":" + waiting.withNano(0)).contains(target));
                facts.add(new Fact("automation:" + stateId + ":current_cycle", "automation", "current_cycle", current));
            });
            List<Map<String, Object>> attempts = jdbc.queryForList("""
                    SELECT attempt_id, attempt_status, error_code, error_message, attempted_at
                    FROM scheduled_client_message_attempts WHERE state_id=? ORDER BY attempt_id DESC LIMIT 6
                    """, stateId);
            attempts.stream().limit(5).forEach(row -> addRow(facts, "attempt:" + number(row, "attempt_id"), "automation_attempt", row,
                    "attempt_id", "attempt_status", "error_code", "error_message", "attempted_at"));
            if (attempts.size() > 5) facts.add(new Fact("automation:" + stateId + ":history_coverage", "history_coverage", "attempt_history_truncated", "Показаны только 5 последних попыток; отсутствие более ранних действий этим срезом не подтверждается"));
        }
        return new Evidence(managerId + ":" + family + ":" + (entityId == null ? string(source, "entity_key") : entityId),
                managerId, currentSourceId, "RESOLVED".equals(string(source, "item_status")), List.copyOf(facts), observedAt);
    }

    public List<Long> scan(long afterId, int limit) {
        return scan(afterId, limit, false);
    }

    public List<Long> scanCurrent(long afterId, int limit) {
        return scan(afterId, limit, true);
    }

    private List<Long> scan(long afterId, int limit, boolean currentOnly) {
        return jdbc.queryForList("""
                SELECT latest.source_id
                FROM (
                    SELECT MAX(i.control_entity_id) AS source_id
                    FROM manager_daily_control_concrete_items i
                    JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                    GROUP BY c.manager_id,
                        CASE WHEN i.entity_type LIKE 'COMMON_INVOICE%' THEN 'COMMON_INVOICE'
                             WHEN i.entity_type LIKE 'CLIENT_CHAT%' THEN 'CLIENT_CHAT' ELSE i.entity_type END,
                        COALESCE(CAST(i.entity_id AS CHAR),i.entity_key)
                ) latest
                JOIN manager_daily_control_concrete_items current_item ON current_item.control_entity_id=latest.source_id
                JOIN manager_daily_controls current_control ON current_control.daily_control_id=current_item.control_id
                WHERE latest.source_id>? AND current_item.item_status<>'RESOLVED'
                  AND (?=FALSE OR (current_item.item_status='OPEN' AND current_control.control_date=(
                      SELECT MAX(d.control_date) FROM manager_daily_controls d WHERE d.manager_id=current_control.manager_id)))
                ORDER BY latest.source_id LIMIT ?
                """, Long.class, afterId, currentOnly, limit);
    }

    private List<Long> collectOrder(List<Fact> facts, long orderId, long managerId) {
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT o.order_id, s.order_status_title AS status, o.order_amount, o.order_sum,
                       o.client_message_generation, o.order_waiting_for_client,
                       COALESCE(o.order_status_changed_at,TIMESTAMP(o.order_changed),TIMESTAMP(o.order_created)) AS cycle_started_at,
                       COALESCE(o.order_waiting_for_client_changed_at,o.order_status_changed_at,
                           TIMESTAMP(o.order_changed),TIMESTAMP(o.order_created)) AS waiting_cycle_started_at
                FROM orders o LEFT JOIN order_statuses s ON s.order_status_id=o.order_status
                WHERE o.order_id=? AND o.order_manager=?
                """, orderId, managerId);
        if (orders.isEmpty()) {
            facts.add(new Fact("order:" + orderId + ":availability", "coverage", "availability",
                    "Заказ отсутствует или больше не относится к менеджеру"));
            return List.of();
        }
        Map<String, Object> order = orders.getFirst();
        addRow(facts, "order:" + orderId, "order", order, "order_id", "status", "order_amount", "order_sum",
                "client_message_generation", "order_waiting_for_client", "cycle_started_at", "waiting_cycle_started_at");
        // A delivered reminder proves contact, not completion of the customer's review or the order.
        facts.add(new Fact("order:" + orderId + ":resolution_coverage", "coverage", "business_resolution",
                "Доставка напоминания не подтверждает проверку клиентом, оплату или завершение заказа; результат проверяется отдельно"));
        List<Long> states = new ArrayList<>();
        LocalDateTime cycle = timestamp(order.get("cycle_started_at"));
        LocalDateTime waitingCycle = timestamp(order.get("waiting_cycle_started_at"));
        if (cycle != null && waitingCycle != null) {
            states.addAll(jdbc.queryForList("""
                    SELECT state_id FROM scheduled_client_message_state
                    WHERE order_id=? AND target_key IN (?,?,?) ORDER BY state_id DESC LIMIT 41
                    """, Long.class, orderId, "order:" + orderId + ":" + cycle.withNano(0),
                    "payment-return:" + orderId + ":" + cycle.withNano(0),
                    "client-text:" + orderId + ":" + waitingCycle.withNano(0)));
            if (states.size() > 40) {
                facts.add(new Fact("order:" + orderId + ":automation_coverage", "coverage", "automation_states_truncated",
                        "Показаны только 40 состояний текущего цикла заказа; наличие других ошибок не исключено"));
                states = new ArrayList<>(states.subList(0, 40));
            }
        } else facts.add(new Fact("order:" + orderId + ":cycle_coverage", "coverage", "availability",
                "Время текущего цикла заказа отсутствует; история автоматизации не приписывается текущему циклу"));
        List<Map<String, Object>> deliveries = jdbc.queryForList("""
                SELECT q.operation_id,q.card_id,q.state,q.attempts,q.error_code,q.created_at
                FROM manager_client_message_queue q
                JOIN manager_daily_control_concrete_items i ON i.control_entity_id=q.card_id
                JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                WHERE q.command_kind='CONTROL' AND i.entity_type='ORDER' AND i.entity_id=? AND c.manager_id=?
                ORDER BY q.created_at DESC,q.operation_id DESC LIMIT 6
                """, orderId, managerId);
        deliveries.stream().limit(5).forEach(row -> addRow(facts, "manager_delivery:" + string(row, "operation_id"),
                "manager_delivery", row, "card_id", "state", "attempts", "error_code", "created_at"));
        if (!deliveries.isEmpty()) facts.add(new Fact("order:" + orderId + ":manual_delivery_coverage", "history_coverage",
                "manual_delivery_cycle_unverified", "Показана история ручных отправок по дневным карточкам заказа этого менеджера. "
                + "Принадлежность операции текущему циклу и устранение проблемы не подтверждаются одними статусом и временем отправки."));
        if (deliveries.size() > 5) facts.add(new Fact("order:" + orderId + ":manual_delivery_truncated", "history_coverage",
                "manual_delivery_history_truncated", "Показаны только 5 последних ручных операций; отсутствие более ранних действий не подтверждается"));
        return states;
    }

    private static LocalDateTime timestamp(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return value instanceof LocalDateTime timestamp ? timestamp : null;
    }

    /** Existing source cards only; this method never creates tasks or widens manager ownership. */
    public List<Long> sourcesForEntity(String entityType, Long entityId) {
        if (entityId == null || entityType == null) return List.of();
        if ("AUTOMATION_FAILURE".equals(entityType)) {
            return jdbc.queryForList("""
                    SELECT MAX(i.control_entity_id) AS source_id FROM manager_daily_control_concrete_items i
                    JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                    WHERE (i.entity_type='AUTOMATION_FAILURE' AND i.entity_id=?)
                       OR (i.entity_type='ORDER' AND i.entity_id IN (
                           SELECT s.order_id FROM scheduled_client_message_state s WHERE s.state_id=?))
                       OR (i.entity_type LIKE 'COMMON_INVOICE%' AND i.entity_id IN (
                           SELECT o.invoice_id FROM common_invoice_orders o JOIN scheduled_client_message_state s ON s.order_id=o.order_id
                           WHERE s.state_id=? AND o.active_membership=TRUE))
                    GROUP BY c.manager_id,CASE WHEN i.entity_type LIKE 'COMMON_INVOICE%' THEN 'COMMON_INVOICE' ELSE i.entity_type END,i.entity_id
                    ORDER BY source_id DESC LIMIT 50
                    """, Long.class, entityId, entityId, entityId);
        }
        return jdbc.queryForList("""
                SELECT MAX(i.control_entity_id) AS source_id FROM manager_daily_control_concrete_items i
                JOIN manager_daily_controls c ON c.daily_control_id=i.control_id
                WHERE i.entity_id=? AND (i.entity_type=? OR (? LIKE 'COMMON_INVOICE%' AND i.entity_type LIKE 'COMMON_INVOICE%')
                    OR (? LIKE 'CLIENT_CHAT%' AND i.entity_type IN ('CLIENT_CHAT_AUDIT','CLIENT_CHAT_UNANSWERED')))
                GROUP BY c.manager_id ORDER BY source_id DESC LIMIT 50
                """, Long.class, entityId, entityType, entityType, entityType);
    }

    private static void addRow(List<Fact> facts, String prefix, String subject, Map<String, Object> row, String... fields) {
        for (String field : fields) facts.add(new Fact(prefix + ":" + field, subject, field, DiagnosticRedactor.redact(row.get(field))));
    }
    private static void addRecordedText(List<Fact> facts, String reference, String field, Object rawText) {
        String text = DiagnosticRedactor.redact(rawText);
        facts.add(new Fact(reference, "recorded_chat_message", field, text));
        if (text.length() > 2000) {
            facts.add(new Fact(reference + ":coverage", "history_coverage", "recorded_text_truncated",
                    "Сообщение " + reference + " показано частично: первые 2000 символов после удаления секретов. По этому отрывку нельзя делать вывод о полноте всего сообщения или разговора."));
        }
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static String string(Map<String, Object> row, String key) { return row.get(key) == null ? "" : row.get(key).toString(); }
    public record Evidence(String caseKey, long managerId, long sourceTaskId, boolean closed, List<Fact> facts, LocalDateTime observedAt) { }
}
