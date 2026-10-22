package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.client_messages.api.CurrentOrderDeliveryUncertainty;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext.Snapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Contact receipts and order completion are separate facts; comments are never delivery locks. */
@Service
@RequiredArgsConstructor
public class ManagerControlOrderDeliveryState {
    static final String CONFIRMED_CONTACT = "Сообщение клиенту подтверждено; ожидаем ответ до ";
    private final ManagerClientMessageQueue queue;
    private final OrderDeliveryControlContext orders;
    private final CurrentOrderDeliveryUncertainty scheduled;
    private final ManagerControlRepairContactCarry repairCarry;

    void requireSendAllowed(ManagerDailyControlConcreteItem card, Snapshot order) {
        if (queue.controlHistory(order.orderId(), true).stream().filter(row -> sameCycle(row, order))
                .anyMatch(row -> blocks(row.delivery()) || recent(row))) {
            throw conflict("По этому циклу заказа уже есть отправка. Проверьте её результат; повторная отправка сейчас заблокирована.");
        }
        if (repairCarry.latest(order).isPresent()) throw conflict("Предыдущий контакт подтверждён при сверке. Дождитесь срока повторной проверки карточки; новое сообщение сейчас не требуется.");
        requireNoScheduledContact(order, true);
    }

    void requireNoScheduledContact(Snapshot expected, boolean includeClaimed) {
        var current = orders.lock(expected.orderId()).orElseThrow(() -> conflict("Заказ больше не доступен"));
        if (!Objects.equals(expected, current)) throw conflict("Менеджер или цикл заказа изменился. Обновите карточку.");
        if (scheduledUnknown(current, true, includeClaimed)) throw conflict(SCHEDULED_CONTACT_BLOCKED);
        if (scheduledRecent(current, true)) throw conflict(RECENT_SCHEDULED_CONTACT);
    }

    private static final String SCHEDULED_CONTACT_BLOCKED = "Исход автоматической отправки этого заказа не подтверждён. Проверьте существующую операцию через «Починить» или передайте администратору. Не отправляйте повторное сообщение вручную.";
    private static final String RECENT_SCHEDULED_CONTACT = "Автоматическая отправка клиенту уже подтверждена в течение последних двух дней. Дождитесь срока повторной проверки; повторное сообщение сейчас не требуется.";

    String sendBlockedReason(ManagerDailyControlConcreteItem card) {
        Snapshot order = order(card);
        if (order == null) return null;
        if (scheduledUnknown(order)) return SCHEDULED_CONTACT_BLOCKED;
        if (scheduledRecent(order, false)) return RECENT_SCHEDULED_CONTACT;
        if (history(order).stream().anyMatch(row -> blocks(row.delivery())))
            return "Предыдущая ручная отправка ещё не завершена или требует сверки. Не отправляйте сообщение повторно.";
        if (history(order).stream().anyMatch(ManagerControlOrderDeliveryState::recent) || repairCarry.latest(order).isPresent())
            return "Контакт с клиентом уже подтверждён. Дождитесь срока повторной проверки; новое сообщение пока не требуется.";
        return null;
    }

    private boolean scheduledRecent(Snapshot order, boolean lock) {
        String target = "order:" + order.orderId() + ":" + order.cycleAt().withNano(0);
        var waitingAt = order.waitingAt() == null ? order.cycleAt() : order.waitingAt();
        String waitingTarget = order.waitingForClient() ? "client-text:" + order.orderId() + ":" + waitingAt.withNano(0) : null;
        LocalDateTime since = LocalDateTime.now().minusDays(ManagerControlItemActions.MANUAL_FOLLOW_UP_DAYS);
        return lock ? scheduled.hasRecentCurrentDeliveryForUpdate(order.orderId(), target, waitingTarget, since)
                : scheduled.hasRecentCurrentDelivery(order.orderId(), target, waitingTarget, since);
    }

    private boolean scheduledUnknown(Snapshot order) {
        return scheduledUnknown(order, false);
    }

    private boolean scheduledUnknown(Snapshot order, boolean lock) {
        return scheduledUnknown(order, lock, true);
    }

    private boolean scheduledUnknown(Snapshot order, boolean lock, boolean includeClaimed) {
        String target = "order:" + order.orderId() + ":" + order.cycleAt().withNano(0);
        var waitingAt = order.waitingAt() == null ? order.cycleAt() : order.waitingAt();
        String waitingTarget = order.waitingForClient() ? "client-text:" + order.orderId() + ":" + waitingAt.withNano(0) : null;
        return lock ? scheduled.hasUncertainCurrentDeliveryForUpdate(order.orderId(), target, waitingTarget, includeClaimed)
                : scheduled.hasUncertainCurrentDelivery(order.orderId(), target, waitingTarget);
    }

    void requireMutationAllowed(ManagerDailyControlConcreteItem card) {
        requireMutationAllowed(card, true);
    }

    /** Receipt recovery already proved scheduled delivery; guard concurrent manager operations only. */
    void requireNoManagerDeliveryPending(ManagerDailyControlConcreteItem card, String expectedTarget) {
        var order = orders.lock(card.getEntityId()).orElseThrow(() -> conflict("Заказ больше не доступен"));
        if (card.getControl() == null || !Objects.equals(card.getControl().managerId(), order.managerId())
                || !Objects.equals(expectedTarget, "order:" + order.orderId() + ":" + order.cycleAt().withNano(0)))
            throw conflict("Менеджер или цикл заказа изменился во время сверки. Обновите карточку.");
        if (queue.controlHistory(order.orderId(), true).stream().filter(row -> sameCycle(row, order))
                .anyMatch(row -> blocks(row.delivery())))
            throw conflict("Ручная отправка этого заказа ещё требует сверки. Её состояние сохранено; обновите карточку.");
    }

    private void requireMutationAllowed(ManagerDailyControlConcreteItem card, boolean checkScheduled) {
        Snapshot order = isOrder(card) ? orders.lock(card.getEntityId()).orElse(null) : null;
        boolean blocked = order == null ? queue.blockingForCard(card.getId()) != null
                : queue.controlHistory(order.orderId(), true).stream().filter(row -> sameCycle(row, order))
                    .anyMatch(row -> blocks(row.delivery())) || (checkScheduled && scheduledUnknown(order, true));
        if (blocked) throw conflict("Отправка ещё не завершена или требует сверки. Нельзя закрывать карточку или менять её действие до проверки существующей операции.");
    }

    boolean pending(ManagerDailyControlConcreteItem card) {
        if (card == null || card.getId() == null) return false;
        Snapshot order = order(card);
        return order == null ? queue.blockingForCard(card.getId()) != null
                : history(order).stream().anyMatch(row -> blocks(row.delivery())) || scheduledUnknown(order);
    }

    DeliveryOperation delivery(ManagerDailyControlConcreteItem card) {
        Snapshot order = order(card);
        if (order == null) return card.getId() == null ? null : queue.blockingForCard(card.getId());
        return history(order).stream().filter(row -> blocks(row.delivery()) || recent(row))
                .map(ManagerClientMessageQueue.RecordedControl::delivery).findFirst().orElse(null);
    }

    DeliveryOperation delivery(ManagerDailyControlConcreteItem card, String operationId) {
        Snapshot order = order(card);
        return order == null ? null : history(order).stream().map(ManagerClientMessageQueue.RecordedControl::delivery)
                .filter(op -> Objects.equals(op.operationId(), operationId)).findFirst().orElse(null);
    }

    /** Restore a proven contact after legacy refresh/daily-copy loss, without asserting order completion. */
    boolean restoreConfirmedContact(ManagerDailyControlConcreteItem card) {
        Snapshot order = order(card);
        if (order == null || !"ORDER".equals(card.getEntityType())
                || card.getStatus() == ManagerDailyControlItemStatus.DEFERRED) return false;
        var rows = history(order);
        var receipt = rows.stream().filter(ManagerControlOrderDeliveryState::recent)
                .filter(row -> !List.of("Опубликовано", "Выставлен счет").contains(row.source().orderStatus()))
                .findFirst().orElse(null);
        var repair = receipt == null ? repairCarry.latest(order).orElse(null) : null;
        if (receipt == null && repair == null) return false;
        LocalDateTime contactedAt = receipt == null ? repair.touchedAt() : receipt.updatedAt();
        LocalDateTime operationAt = receipt == null ? repair.touchedAt() : receipt.createdAt();
        if (rows.stream().anyMatch(row -> row.createdAt().isAfter(operationAt) && blocks(row.delivery()))) return false;
        LocalDateTime followUp = receipt == null ? repair.followUpAt()
                : receipt.updatedAt().plusDays(ManagerControlItemActions.MANUAL_FOLLOW_UP_DAYS);
        if (card.getStatus() == ManagerDailyControlItemStatus.ACTION_TAKEN
                && card.getFollowUpAt() != null && !card.getFollowUpAt().isBefore(followUp)) return false;
        card.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        card.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        card.setResolvedAt(null);
        card.setAutomaticResolution(false);
        card.setLastManualTouchAt(contactedAt);
        card.setFollowUpAt(followUp);
        card.setComment((receipt == null ? ManagerControlRepairContactCarry.CONFIRMED_REPAIR + "; ожидаем ответ до "
                : CONFIRMED_CONTACT) + followUp + ". Проверка заказа остаётся открытой.");
        return true;
    }

    private Snapshot order(ManagerDailyControlConcreteItem card) {
        if (!isOrder(card)) return null;
        return orders.read(card.getEntityId()).filter(order -> card.getControl() != null && card.getControl().managerId() != null
                && Objects.equals(card.getControl().managerId(), order.managerId())).orElse(null);
    }

    private boolean isOrder(ManagerDailyControlConcreteItem card) {
        return card != null && card.getEntityId() != null && ("ORDER".equals(card.getEntityType()) || "WORKER_ORDER_NEW".equals(card.getEntityType()));
    }

    List<ManagerClientMessageQueue.RecordedControl> history(Snapshot order) {
        return queue.controlHistory(order.orderId()).stream().filter(row -> sameCycle(row, order)).toList();
    }

    static boolean sameCycle(ManagerClientMessageQueue.RecordedControl row, Snapshot order) {
        var frozen = row.source();
        if (!Objects.equals(frozen.orderId(), order.orderId())
                || !Objects.equals(frozen.orderManagerId(), order.managerId())
                || !Objects.equals(frozen.orderStatus(), order.status())) return false;
        if (frozen.sourceGeneration() != null)
            return frozen.sourceGeneration() == order.generation()
                    && Objects.equals(frozen.sourceStatusChangedAt(), order.cycleAt());
        // Old envelopes have no cycle fields. Only a provably newer cycle releases the old fence.
        return !order.cycleAt().isAfter(row.createdAt());
    }

    static boolean recent(ManagerClientMessageQueue.RecordedControl row) {
        return "SENT".equals(row.delivery().status()) && row.delivery().errorCode() == null
                && row.updatedAt().plusDays(ManagerControlItemActions.MANUAL_FOLLOW_UP_DAYS).isAfter(LocalDateTime.now());
    }
    static boolean blocks(DeliveryOperation op) {
        return List.of("QUEUED", "SENDING", "RETRYABLE", "UNKNOWN").contains(op.status())
                || "finalization_required".equals(op.errorCode()) || "context_changed".equals(op.errorCode());
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
