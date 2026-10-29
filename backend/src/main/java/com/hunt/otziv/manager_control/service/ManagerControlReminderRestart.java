package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_chat_control.api.ReminderContactHistory;
import com.hunt.otziv.client_messages.api.PeriodicReminderRestart;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.api.OrderDeliveryControlContext;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The repair button resumes the periodic business task, preserving every unknown original operation. */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ManagerControlReminderRestart {
    private final PeriodicReminderRestart scheduled;
    private final ReminderContactHistory history;
    private final ManagerClientMessageQueue queue;
    private final ManagerClientMessageWorker worker;
    private final OrderDeliveryControlContext orders;
    private final ManagerControlTransactionRunner transactions;
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerControlRepairOutcome outcome;
    private final com.hunt.otziv.client_messages.api.ReviewReminderClosure closure;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<ManagerControlConcreteItemResponse> repair(long cardId, long stateId, Long managerId, Principal actor) {
        var result = repairReminder(cardId,stateId,managerId,actor);
        if (result.isEmpty()) return result;
        com.hunt.otziv.client_messages.api.ReviewReminderClosure.Decision decision;
        try { decision = closure.archiveAfterReminders(stateId); }
        catch (RuntimeException unavailable) {
            log.warn("Reminder restored but archive check failed: stateId={}",stateId,unavailable);
            decision = new com.hunt.otziv.client_messages.api.ReviewReminderClosure.Decision(false,
                    unavailable instanceof ResponseStatusException conflict && conflict.getReason() != null
                        ? conflict.getReason()
                        : "Очередь восстановлена, но автоархив пока не выполнен: проверка истории или готовности заказа недоступна. Повторная отправка старого сообщения не запущена.");
        }
        if (decision == null || decision.reason() == null || decision.reason().isBlank()) return result;
        if (!decision.archived()) {
            String reason = decision.reason();
            return Optional.of(transactions.contactReservation(() -> {
                var card = cards.findByIdForUpdate(cardId).orElseThrow();
                return outcome.appendArchiveDecision(card,reason);
            }));
        }
        return Optional.of(transactions.contactReservation(() -> {
            var card = cards.findByIdForUpdate(cardId).orElseThrow();
            return outcome.resolveRepairedConcreteItem(card,card.getControl(),
                    "Заказ переведён в архив после семи подтверждённых напоминаний. Уведомления клиенту и ответственным сохранены в очереди; срок предложения нового заказа записан в истории.",
                    actor,"Автоматический архив после проверки истории");
        }));
    }

    private Optional<ManagerControlConcreteItemResponse> repairReminder(long cardId, long stateId, Long managerId, Principal actor) {
        var deferred = scheduled.checkDeferredReminderRestart(stateId, managerId);
        if (deferred.isPresent() && !hasPendingContact(deferred.get().source().order())) {
            var check = deferred.get();
            return Optional.of(transactions.contactReservation(() -> {
                var card = cards.findByIdForUpdate(cardId).orElseThrow();
                requireDeferredSource(check, managerId);
                if (card.getControl() == null || !Objects.equals(card.getControl().managerId(), managerId)
                        || !("ORDER".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), check.source().order().orderId())
                            || "AUTOMATION_FAILURE".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), stateId))) throw conflict();
                String actorName = actor == null ? "" : actor.getName();
                if (actorName.isBlank()) throw conflict();
                LocalDateTime next = scheduled.restartDeferredReminder(check, null, actorName);
                return outcome.recordReminderScheduled(card, deferredNote(next), actor, next);
            }));
        }
        var inspected = scheduled.checkStaleReminderRestart(stateId, managerId);
        if (inspected.isEmpty()) {
            var reserved = scheduled.checkManualReservationReminder(stateId, managerId);
            if (reserved.isPresent()) return repairCancelledManualReservation(cardId, reserved.get(), managerId, actor);
            return alreadyScheduled(cardId, stateId, managerId, actor);
        }
        var check = inspected.get();
        var source = check.order();
        var checkedManual = new HashSet<String>();
        for (var row : queue.controlHistory(source.orderId()).stream()
                .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, source))
                .filter(row -> ManagerControlOrderDeliveryState.blocks(row.delivery())).toList()) {
            if (row.createdAt().isAfter(LocalDateTime.now().minusDays(2)))
                return Optional.empty();
            if (!cancelledBeforeDispatch(row)) {
                if (!"UNKNOWN".equals(row.delivery().status()) || !worker.reconcileExisting(row.delivery().operationId()))
                    return Optional.empty();
            }
            checkedManual.add(row.delivery().operationId());
        }
        LocalDateTime contact;
        try {
            contact = history.latestReminderContact(check.delivery().clientId(), check.delivery().groupId(),
                    LocalDateTime.now().minusDays(2));
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Не удалось прочитать последние сообщения WhatsApp. Очередь сохранена; попробуйте «Проверить и починить» после восстановления связи.");
        }
        LocalDateTime latestChatContact = contact;
        return Optional.of(transactions.contactReservation(() -> {
            var card = cards.findByIdForUpdate(cardId).orElseThrow();
            var current = orders.lock(source.orderId()).orElseThrow();
            if (!Objects.equals(source, current) || card.getControl() == null
                    || !Objects.equals(card.getControl().managerId(), managerId)
                    || !("ORDER".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), source.orderId())
                        || "AUTOMATION_FAILURE".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), stateId)))
                throw conflict();
            var rows = queue.controlHistoryForSettlement(source.orderId()).stream()
                    .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, current)).toList();
            var pending = rows.stream().filter(row -> ManagerControlOrderDeliveryState.blocks(row.delivery())).toList();
            for (var row : pending) {
                if (!checkedManual.contains(row.delivery().operationId())
                        || !(cancelledBeforeDispatch(row) || "UNKNOWN".equals(row.delivery().status())
                            && "whatsapp_history_checked_no_receipt".equals(row.delivery().errorCode()))
                        || !Objects.equals(row.source().managerClientId(), check.delivery().clientId())
                        || !Objects.equals(row.source().groupId(), check.delivery().groupId())) throw conflict();
            }
            LocalDateTime latest = latestChatContact;
            for (var row : rows) {
                if ("SENT".equals(row.delivery().status()) && row.delivery().errorCode() == null
                        && row.updatedAt().isAfter(latest)) latest = row.updatedAt();
                if (pending.contains(row) && row.createdAt().isAfter(latest)) latest = row.createdAt();
            }
            String actorName = actor == null ? "" : actor.getName();
            if (actorName.isBlank()) throw conflict();
            LocalDateTime next = scheduled.restartStaleReminder(check, latest, actorName);
            for (var row : pending) queue.supersedeStaleReminder(row, actorName, next);
            return outcome.recordReminderScheduled(card, note(next), actor, next);
        }));
    }

    /** Only the periodic queue changes. The board refresh resolves its technical source normally. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<LocalDateTime> recoverDeferredAutomatically(long stateId, Long managerId) {
        if (managerId == null) return Optional.empty();
        var inspected = scheduled.checkDeferredReminderRestart(stateId, managerId);
        if (inspected.isEmpty() || hasPendingContact(inspected.get().source().order())) return Optional.empty();
        var check = inspected.get();
        return Optional.of(transactions.contactReservation(() -> {
            requireDeferredSource(check, managerId);
            return scheduled.restartDeferredReminder(check, null, "system:periodic-reminder-recovery");
        }));
    }

    private boolean hasPendingContact(OrderDeliveryControlContext.Snapshot source) {
        return queue.controlHistory(source.orderId()).stream()
                .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, source))
                .anyMatch(row -> ManagerControlOrderDeliveryState.blocks(row.delivery()));
    }

    private void requireDeferredSource(PeriodicReminderRestart.DeferredReminderCheck check, Long managerId) {
        var source = check.source().order();
        var current = orders.lock(source.orderId()).orElseThrow();
        if (!Objects.equals(source, current) || !Objects.equals(current.managerId(), managerId)
                || queue.controlHistoryForSettlement(current.orderId()).stream()
                    .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, current))
                    .anyMatch(row -> ManagerControlOrderDeliveryState.blocks(row.delivery()))) throw conflict();
    }

    private String deferredNote(LocalDateTime next) {
        return "Очередь напоминаний восстановлена. Следующее плановое напоминание — "
                + next.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneId.of("Asia/Irkutsk"))
                    .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                + " (Иркутск), после полного интервала ожидания. Старая неподтверждённая операция сохранена в журнале без повтора. "
                + "Подтверждение доставки, оплаты или проверки отзывов не подставлялось.";
    }

    private Optional<ManagerControlConcreteItemResponse> repairCancelledManualReservation(long cardId,
            PeriodicReminderRestart.ReservedReminderCheck check, Long managerId, Principal actor) {
        var source = check.order();
        var cancelled = queue.controlHistory(source.orderId()).stream()
                .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, source))
                .filter(row -> ManagerControlOrderDeliveryState.blocks(row.delivery())).toList();
        if (cancelled.isEmpty() || cancelled.stream().anyMatch(row -> !cancelledBeforeDispatch(row)
                || row.createdAt().isAfter(LocalDateTime.now().minusDays(2)))) return Optional.empty();
        LocalDateTime latestContact;
        try {
            latestContact = history.latestReminderContact(check.clientId(), check.groupId(), LocalDateTime.now().minusDays(2));
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Не удалось прочитать последние сообщения WhatsApp. Очередь сохранена; попробуйте «Проверить и починить» после восстановления связи.");
        }
        if (latestContact == null) throw conflict();
        LocalDateTime checkedContact = latestContact;
        return Optional.of(transactions.contactReservation(() -> {
            var card = cards.findByIdForUpdate(cardId).orElseThrow();
            var current = orders.lock(source.orderId()).orElseThrow();
            if (!Objects.equals(source, current) || card.getControl() == null
                    || !Objects.equals(card.getControl().managerId(), managerId)
                    || !("ORDER".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), source.orderId())
                        || "AUTOMATION_FAILURE".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), check.stateId())))
                throw conflict();
            var rows = queue.controlHistoryForSettlement(source.orderId()).stream()
                    .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, current)).toList();
            var pending = rows.stream().filter(row -> ManagerControlOrderDeliveryState.blocks(row.delivery())).toList();
            if (pending.size() != cancelled.size() || !pending.containsAll(cancelled)) throw conflict();
            LocalDateTime latest = checkedContact;
            for (var row : rows) {
                if (pending.contains(row) && (!cancelledBeforeDispatch(row)
                        || !Objects.equals(row.source().managerClientId(), check.clientId())
                        || !Objects.equals(row.source().groupId(), check.groupId()))) throw conflict();
                if ((pending.contains(row) || "SENT".equals(row.delivery().status()) && row.delivery().errorCode() == null)
                        && row.updatedAt().isAfter(latest)) latest = row.updatedAt();
            }
            String actorName = actor == null ? "" : actor.getName();
            if (actorName.isBlank()) throw conflict();
            LocalDateTime next = scheduled.resumeAfterCancelledManualContact(check, latest);
            for (var row : pending) queue.supersedeCancelledReservation(row, actorName, next, check);
            return outcome.recordReminderScheduled(card, note(next, true), actor, next);
        }));
    }

    private Optional<ManagerControlConcreteItemResponse> alreadyScheduled(long cardId, long stateId, Long managerId, Principal actor) {
        var existing = scheduled.pendingReminderRestart(stateId, managerId);
        if (existing.isEmpty()) return Optional.empty();
        return Optional.of(transactions.contactReservation(() -> {
            var card = cards.findByIdForUpdate(cardId).orElseThrow();
            var current = orders.lock(existing.get().order().orderId()).orElseThrow();
            var latest = scheduled.pendingReminderRestart(stateId, managerId).orElseThrow(this::conflict);
            if (queue.controlHistory(current.orderId(), true).stream()
                    .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, current))
                    .anyMatch(row -> ManagerControlOrderDeliveryState.blocks(row.delivery()))) throw conflict();
            if (!Objects.equals(current, existing.get().order()) || !Objects.equals(current, latest.order())
                    || card.getControl() == null || !Objects.equals(card.getControl().managerId(), managerId)
                    || !("ORDER".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), current.orderId())
                        || "AUTOMATION_FAILURE".equals(card.getEntityType()) && Objects.equals(card.getEntityId(), stateId)))
                throw conflict();
            return outcome.recordReminderScheduled(card, latest.deferred() ? deferredNote(latest.nextAt()) : note(latest.nextAt()), actor, latest.nextAt());
        }));
    }

    private String note(LocalDateTime next) {
        return note(next, false);
    }

    private String note(LocalDateTime next, boolean cancelledBeforeSend) {
        return "Очередь напоминаний восстановлена. Следующая автоматическая проверка — "
                + next.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneId.of("Asia/Irkutsk"))
                    .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                + (cancelledBeforeSend
                    ? " (Иркутск). История WhatsApp проверена; отменённая до отправки ручная команда сохранена в истории и больше не блокирует очередь. "
                    : " (Иркутск). История WhatsApp проверена; старая неподтверждённая операция сохранена без повтора. ")
                + "Дальнейшее действие определяется этапом заказа и числом подтверждённых напоминаний. Оплата не менялась.";
    }

    // The worker sets this pair only before a provider call. The current actor/source are
    // checked again by the repair, and the cancelled command is audited, never resent.
    private boolean cancelledBeforeDispatch(ManagerClientMessageQueue.RecordedControl row) {
        return "FAILED".equals(row.delivery().status()) && "context_changed".equals(row.delivery().errorCode());
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Во время сверки изменился заказ или отправка. Обновите карточку: повторное сообщение не создано.");
    }
}
