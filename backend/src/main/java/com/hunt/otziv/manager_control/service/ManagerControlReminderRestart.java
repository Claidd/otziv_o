package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
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
public class ManagerControlReminderRestart {
    private final ScheduledClientMessageService scheduled;
    private final ClientChatMessageReconciliationService history;
    private final ManagerClientMessageQueue queue;
    private final ManagerClientMessageWorker worker;
    private final OrderDeliveryControlContext orders;
    private final ManagerControlTransactionRunner transactions;
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerControlRepairOutcome outcome;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<ManagerControlConcreteItemResponse> repair(long cardId, long stateId, Long managerId, Principal actor) {
        var inspected = scheduled.checkStaleReminderRestart(stateId, managerId);
        if (inspected.isEmpty()) return alreadyScheduled(cardId, stateId, managerId, actor);
        var check = inspected.get();
        var source = check.order();
        var checkedManual = new HashSet<String>();
        for (var row : queue.controlHistory(source.orderId()).stream()
                .filter(row -> ManagerControlOrderDeliveryState.sameCycle(row, source))
                .filter(row -> ManagerControlOrderDeliveryState.blocks(row.delivery())).toList()) {
            if (!"UNKNOWN".equals(row.delivery().status()) || row.createdAt().isAfter(LocalDateTime.now().minusDays(2)))
                return Optional.empty();
            if (!worker.reconcileExisting(row.delivery().operationId())) return Optional.empty();
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
                if (!checkedManual.contains(row.delivery().operationId()) || !"UNKNOWN".equals(row.delivery().status())
                        || !"whatsapp_history_checked_no_receipt".equals(row.delivery().errorCode())
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
            return outcome.recordReminderScheduled(card, note(latest.nextAt()), actor, latest.nextAt());
        }));
    }

    private String note(LocalDateTime next) {
        return "Очередь напоминаний восстановлена. Следующая проверка и допустимая отправка — "
                + next.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
                + " (Иркутск). История WhatsApp проверена; старая неподтверждённая операция сохранена без повтора. "
                + "Завершение заказа и оплата не менялись.";
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Во время сверки изменился заказ или отправка. Обновите карточку: повторное сообщение не создано.");
    }
}
