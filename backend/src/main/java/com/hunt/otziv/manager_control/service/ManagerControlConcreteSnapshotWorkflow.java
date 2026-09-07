package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemDetailResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlGroup;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlConcreteSnapshotWorkflow {

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlCardLifecycle cardLifecycle;

    private final ManagerControlSlaPolicy slaPolicy;

    private final ManagerControlConcretePresenter concretePresenter;

    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;

    ManagerControlItemDetailResponse detailItem(Manager manager, ManagerDailyControlItem item, LocalDate today, boolean syncConcrete) {
        List<ManagerControlConcreteItemResponse> freshExamples = detailExamples(manager, item, today);
        List<ManagerControlConcreteItemResponse> examples = syncConcrete ? syncConcreteExamples(item, freshExamples) : readConcreteExamples(item, freshExamples);
        examples = examples.stream().map(example -> slaPolicy.decorateConcreteSla(item, example)).toList();
        return new ManagerControlItemDetailResponse(item.getId(), item.getItemKey(), item.getItemType().name(), item.getReasonCode(), reasonLabel(item), item.getSectionCode(), item.getLabel(), item.getTargetUrl(), item.getCount(), item.getSeverity().name(), item.getGroup().name(), item.getStatus().name(), item.getActionType() == null ? null : item.getActionType().name(), item.getComment(), examples, Math.max(0, item.getCount() - examples.size()), item.getCreatedAt(), item.getUpdatedAt(), item.getResolvedAt());
    }

    List<ManagerControlConcreteItemResponse> detailExamples(Manager manager, ManagerDailyControlItem item, LocalDate today) {
        return problemExamples.detailExamples(manager, item, today);
    }

    List<ManagerControlConcreteItemResponse> readConcreteExamples(ManagerDailyControlItem parentItem, List<ManagerControlConcreteItemResponse> freshExamples) {
        if (parentItem == null || parentItem.getId() == null) {
            return List.of();
        }
        List<ManagerDailyControlConcreteItem> storedExamples = dailyControlConcreteItemRepository.findByParentItem(parentItem);
        if (storedExamples.isEmpty()) {
            return freshExamples;
        }
        Map<String, ManagerControlConcreteItemResponse> freshByKey = freshExamples.stream().collect(Collectors.toMap(this::concreteEntityKey, Function.identity(), (left, right) -> left));
        Map<String, ManagerDailyControlConcreteItem> storedByKey = storedExamples.stream().collect(Collectors.toMap(ManagerDailyControlConcreteItem::getEntityKey, Function.identity(), (left, right) -> left));
        Set<String> freshKeys = freshByKey.keySet();
        resolveStaleConcreteItems(parentItem, storedByKey, freshKeys);
        boolean reopenedUnanswered = false;
        for (ManagerDailyControlConcreteItem stored : storedExamples) {
            if (freshKeys.contains(stored.getEntityKey()) && reopenActiveClientChatUnanswered(stored)) {
                dailyControlConcreteItemRepository.save(stored);
                reopenedUnanswered = true;
            }
        }
        if (reopenedUnanswered) {
            cardLifecycle.reopenParentItemIfConcreteOpen(parentItem);
        }
        List<ManagerControlConcreteItemResponse> visibleStored = storedExamples.stream().filter(item -> item.getStatus() != ManagerDailyControlItemStatus.RESOLVED).filter(item -> !isClosedClientChatUnansweredConcrete(item)).filter(item -> !isConcreteSnoozed(item)).sorted(Comparator.comparing(ManagerDailyControlConcreteItem::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(ManagerDailyControlConcreteItem::getId, Comparator.nullsLast(Long::compareTo))).map(item -> {
            if (reopenQueuedChatBindingRepair(item)) {
                dailyControlConcreteItemRepository.save(item);
            }
            ManagerControlConcreteItemResponse fresh = freshByKey.get(item.getEntityKey());
            return concretePresenter.concreteItemResponse(item, fresh == null ? null : fresh.contactText(), fresh == null ? null : fresh.specialistName());
        }).toList();
        if (freshExamples.isEmpty()) {
            return visibleStored;
        }
        List<ManagerControlConcreteItemResponse> merged = new ArrayList<>(visibleStored);
        freshExamples.stream().filter(example -> !storedByKey.containsKey(concreteEntityKey(example))).forEach(merged::add);
        return merged;
    }

    boolean isClosedClientChatUnansweredConcrete(ManagerDailyControlConcreteItem item) {
        return item != null && ENTITY_CLIENT_CHAT_UNANSWERED.equals(item.getEntityType()) && item.getStatus() != ManagerDailyControlItemStatus.OPEN && item.getStatus() != ManagerDailyControlItemStatus.DEFERRED;
    }

    List<ManagerControlConcreteItemResponse> syncConcreteExamples(ManagerDailyControlItem parentItem, List<ManagerControlConcreteItemResponse> examples) {
        if (parentItem == null) {
            return List.of();
        }
        Map<String, ManagerControlConcreteItemResponse> uniqueExamples = examples.stream().collect(Collectors.toMap(this::concreteEntityKey, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, ManagerDailyControlConcreteItem> existing = dailyControlConcreteItemRepository.findByParentItemForUpdate(parentItem).stream().collect(Collectors.toMap(ManagerDailyControlConcreteItem::getEntityKey, Function.identity(), (left, right) -> left));
        Set<String> freshKeys = uniqueExamples.keySet();
        resolveStaleConcreteItems(parentItem, existing, freshKeys);
        if (uniqueExamples.isEmpty()) {
            return List.of();
        }
        List<ManagerControlConcreteItemResponse> synced = new ArrayList<>();
        boolean reopenedUnanswered = false;
        for (Map.Entry<String, ManagerControlConcreteItemResponse> entry : uniqueExamples.entrySet()) {
            String key = entry.getKey();
            ManagerControlConcreteItemResponse example = entry.getValue();
            ManagerDailyControlConcreteItem concreteItem = existing.get(key);
            boolean created = false;
            if (concreteItem == null) {
                concreteItem = new ManagerDailyControlConcreteItem();
                concreteItem.setControl(parentItem.getControl());
                concreteItem.setParentItem(parentItem);
                concreteItem.setEntityKey(key);
                concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
                created = true;
            }
            boolean changed = applyConcreteItemSnapshot(concreteItem, example);
            if (reopenActiveClientChatUnanswered(concreteItem)) {
                changed = true;
                reopenedUnanswered = true;
            }
            if (reopenQueuedChatBindingRepair(concreteItem)) {
                changed = true;
            }
            if (reopenConcreteItemIfFollowUpDue(concreteItem)) {
                changed = true;
            }
            if (reopenResolvedConcreteItemIfExpired(concreteItem)) {
                changed = true;
            }
            if (reopenActiveAutomationConcreteItem(concreteItem)) {
                changed = true;
            }
            if (reopenResolvedConcreteItemStillActive(parentItem, concreteItem)) {
                changed = true;
            }
            if (isResolvedConcreteItemHiddenForToday(concreteItem)) {
                if (created || changed) {
                    dailyControlConcreteItemRepository.save(concreteItem);
                }
                continue;
            }
            if (isConcreteSnoozed(concreteItem)) {
                if (created || changed) {
                    dailyControlConcreteItemRepository.save(concreteItem);
                }
                continue;
            }
            ManagerDailyControlConcreteItem saved = created || changed ? dailyControlConcreteItemRepository.save(concreteItem) : concreteItem;
            existing.put(key, saved);
            synced.add(concretePresenter.concreteItemResponse(saved, example.contactText(), example.specialistName()));
        }
        if (reopenedUnanswered) {
            cardLifecycle.reopenParentItemIfConcreteOpen(parentItem);
        }
        return synced;
    }

    boolean reopenActiveClientChatUnanswered(ManagerDailyControlConcreteItem item) {
        if (item == null || !ENTITY_CLIENT_CHAT_UNANSWERED.equals(item.getEntityType()) || item.getStatus() == ManagerDailyControlItemStatus.OPEN) {
            return false;
        }
        item.setStatus(ManagerDailyControlItemStatus.OPEN);
        item.setActionType(null);
        item.setResolvedAt(null);
        item.setAutomaticResolution(false);
        item.setFollowUpAt(null);
        return true;
    }

    void resolveStaleConcreteItems(ManagerDailyControlItem parentItem, Map<String, ManagerDailyControlConcreteItem> existing, Set<String> freshKeys) {
        if (parentItem == null || existing == null || existing.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ManagerDailyControlConcreteItem item : existing.values()) {
            if (item == null || item.getStatus() == ManagerDailyControlItemStatus.RESOLVED || freshKeys.contains(item.getEntityKey())) {
                continue;
            }
            cardLifecycle.recordConcreteEpisode(item, ManagerDailyControlItemStatus.RESOLVED, true);
            item.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            item.setActionType(ManagerDailyControlActionType.RESOLVED);
            item.setComment("Проблема больше не актуальна и закрыта автоматически");
            item.setResolvedAt(now);
            item.setAutomaticResolution(true);
            item.setFollowUpAt(null);
            item.setLastManualTouchAt(null);
            dailyControlConcreteItemRepository.save(item);
        }
    }

    boolean applyConcreteItemSnapshot(ManagerDailyControlConcreteItem item, ManagerControlConcreteItemResponse example) {
        boolean changed = false;
        LocalDateTime firstObservedAt = example.firstObservedAt();
        if (shouldAlignFirstObservedAt(item.getCreatedAt(), firstObservedAt)) {
            item.setCreatedAt(firstObservedAt);
            changed = true;
        }
        String entityType = limit(safe(example.type()).isBlank() ? "UNKNOWN" : example.type(), 40);
        String title = limit(safe(example.title()).isBlank() ? "Карточка контроля" : example.title(), 220);
        String subtitle = limit(example.subtitle(), 500);
        String statusLabel = limit(example.status(), 120);
        String reason = limit(example.reason(), 500);
        String targetUrl = limit(example.targetUrl(), 500);
        String orderDetailsId = limit(example.orderDetailsId(), 36);
        String chatUrl = limit(example.chatUrl(), 500);
        if (!Objects.equals(item.getEntityType(), entityType)) {
            item.setEntityType(entityType);
            changed = true;
        }
        if (!Objects.equals(item.getEntityId(), example.entityId())) {
            item.setEntityId(example.entityId());
            changed = true;
        }
        if (!Objects.equals(item.getTitle(), title)) {
            item.setTitle(title);
            changed = true;
        }
        if (!Objects.equals(item.getSubtitle(), subtitle)) {
            item.setSubtitle(subtitle);
            changed = true;
        }
        if (!Objects.equals(item.getStatusLabel(), statusLabel)) {
            item.setStatusLabel(statusLabel);
            changed = true;
        }
        if (!Objects.equals(item.getAgeDays(), example.ageDays())) {
            item.setAgeDays(example.ageDays());
            changed = true;
        }
        if (!Objects.equals(item.getReason(), reason)) {
            item.setReason(reason);
            changed = true;
        }
        if (!Objects.equals(item.getTargetUrl(), targetUrl)) {
            item.setTargetUrl(targetUrl);
            changed = true;
        }
        if (!Objects.equals(item.getOrderDetailsId(), orderDetailsId)) {
            item.setOrderDetailsId(orderDetailsId);
            changed = true;
        }
        if (!Objects.equals(item.getChatUrl(), chatUrl)) {
            item.setChatUrl(chatUrl);
            changed = true;
        }
        if ("RISK".equals(entityType)) {
            String workerExplanation = limit(example.workerExplanation(), 1000);
            if (!Objects.equals(item.getWorkerExplanation(), workerExplanation)) {
                item.setWorkerExplanation(workerExplanation);
                changed = true;
            }
            if (!Objects.equals(item.getWorkerExplanationAt(), example.workerExplanationAt())) {
                item.setWorkerExplanationAt(example.workerExplanationAt());
                changed = true;
            }
        }
        return changed;
    }

    boolean shouldAlignFirstObservedAt(LocalDateTime storedAt, LocalDateTime sourceAt) {
        if (sourceAt == null) {
            return false;
        }
        return storedAt == null || Math.abs(Duration.between(storedAt, sourceAt).getSeconds()) > 60;
    }

    boolean reopenQueuedChatBindingRepair(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getStatus() == ManagerDailyControlItemStatus.OPEN || concreteItem.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        ManagerDailyControlItem parent = concreteItem.getParentItem();
        if (parent == null || !"CHAT_BINDING_ISSUES".equals(parent.getReasonCode())) {
            return false;
        }
        String comment = safe(concreteItem.getComment()).toLowerCase(Locale.ROOT);
        if (!comment.contains("фоновая синхронизация whatsapp-групп") && !comment.contains("повторная проверка через")) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    boolean isResolvedConcreteItemHiddenForToday(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        return true;
    }

    boolean reopenResolvedConcreteItemIfExpired(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        LocalDateTime resolvedAt = concreteItem.getResolvedAt();
        if (resolvedAt == null || !resolvedAt.toLocalDate().isBefore(LocalDate.now())) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    boolean reopenActiveAutomationConcreteItem(ManagerDailyControlConcreteItem concreteItem) {
        if (concreteItem == null || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED || concreteItem.getParentItem() == null || !"AUTOMATION_FAILURES".equals(concreteItem.getParentItem().getReasonCode())) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    boolean reopenResolvedConcreteItemStillActive(ManagerDailyControlItem parentItem, ManagerDailyControlConcreteItem concreteItem) {
        if (parentItem == null || concreteItem == null || parentItem.getStatus() != ManagerDailyControlItemStatus.OPEN || parentItem.getGroup() != ManagerDailyControlGroup.ACTION || concreteItem.getStatus() != ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        reopenConcreteItem(concreteItem);
        return true;
    }

    void reopenConcreteItem(ManagerDailyControlConcreteItem concreteItem) {
        concreteItem.setStatus(ManagerDailyControlItemStatus.OPEN);
        concreteItem.setActionType(null);
        concreteItem.setComment(null);
        concreteItem.setResolvedAt(null);
        concreteItem.setAutomaticResolution(false);
        concreteItem.setFollowUpAt(null);
        concreteItem.setLastManualTouchAt(null);
        clearWorkerTelegramState(concreteItem);
    }

    String concreteEntityKey(ManagerControlConcreteItemResponse example) {
        String type = safe(example.type()).isBlank() ? "UNKNOWN" : example.type();
        Long id = example.entityId();
        if (id != null) {
            return type + ":" + id;
        }
        return type + ":" + safe(example.title()) + ":" + safe(example.targetUrl());
    }

    boolean isConcreteSnoozed(ManagerDailyControlConcreteItem item) {
        return item != null && !ENTITY_CLIENT_CHAT_UNANSWERED.equals(item.getEntityType()) && item.getFollowUpAt() != null && item.getFollowUpAt().isAfter(LocalDateTime.now()) && item.getStatus() != ManagerDailyControlItemStatus.OPEN;
    }

    boolean reopenConcreteItemIfFollowUpDue(ManagerDailyControlConcreteItem item) {
        if (item == null || item.getFollowUpAt() == null || item.getFollowUpAt().isAfter(LocalDateTime.now()) || item.getStatus() == ManagerDailyControlItemStatus.OPEN || item.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            return false;
        }
        reopenConcreteItem(item);
        return true;
    }

    void clearWorkerTelegramState(ManagerDailyControlConcreteItem concreteItem) {
        concreteItem.setWorkerNotificationAttemptedAt(null);
        concreteItem.setWorkerNotificationUserId(null);
        concreteItem.setWorkerNotificationSentAt(null);
        concreteItem.setWorkerNotificationAcceptedAt(null);
        concreteItem.setWorkerNotificationAcceptedByUserId(null);
        concreteItem.setWorkerNotificationFailureReason(null);
        concreteItem.setWorkerExplanationRequestedAt(null);
        concreteItem.setWorkerExplanationPromptedAt(null);
        concreteItem.setWorkerExplanation(null);
        concreteItem.setWorkerExplanationAt(null);
        concreteItem.setWorkerExplanationByUserId(null);
        concreteItem.setWorkerReminderSentAt(null);
        concreteItem.setWorkerReminderCount(0);
    }

    String reasonLabel(ManagerDailyControlItem item) {
        if (item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS) {
            return "Просрочка в статусе заказа";
        }
        if (item.getItemType() == ManagerDailyControlItemType.WORKER_SECTION) {
            return item.getGroup() == ManagerDailyControlGroup.ACTION ? "Раздел специалиста требует действия" : "Рабочая нагрузка специалиста";
        }
        return switch(safe(item.getReasonCode())) {
            case "OVERDUE_ORDERS" ->
                "Есть заказы без нужного действия";
            case "OPEN_RISKS" ->
                "Есть открытые риски специалистов";
            case "REQUIRES_ATTENTION" ->
                "Есть заказы в статусе требует внимания";
            case "COMMON_INVOICES" ->
                "Есть общие счета с ошибкой или зависшим статусом";
            case "PAYMENT_INTEGRITY" ->
                "Оплаченный заказ ошибочно вошел в повторный платежный цикл";
            case "PUBLICATION_DATE_ISSUES" ->
                "Есть заказы в публикации с отзывами без назначенной даты";
            case "CHAT_BINDING_ISSUES" ->
                "Есть заказы с непривязанной группой соцсети";
            case "WORKER_ACTIONS" ->
                "Есть задачи специалистов, которые надо разобрать";
            case "ORDERS_WORKLOAD" ->
                "Общий объем рабочих заказов";
            case "WORKER_WORKLOAD" ->
                "Нагрузка специалистов";
            default ->
                item.getLabel();
        };
    }

    String limit(String value, int maxLength) {
        return problemExamples.limit(value, maxLength);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
