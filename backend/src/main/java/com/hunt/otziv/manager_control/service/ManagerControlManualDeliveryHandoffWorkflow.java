package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.api.UncertainClientMessageDelivery;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The manager's decision on an autoresponder send whose outcome stayed unconfirmed: the message is in the chat (count
 * it), it is not (send it again), or do not send it (the manager leads the client). The former handoff that only
 * parked the task stays for administrators of common-invoice cards.
 */
@Service
@RequiredArgsConstructor
public class ManagerControlManualDeliveryHandoffWorkflow {
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerControlAccessPolicy access;
    private final ManagerPermissionService permissions;
    private final ManagerAutomationFailureService failures;
    private final UncertainClientMessageDelivery messages;
    private final ManagerControlCardLifecycle lifecycle;
    private final ManagerControlConcretePresenter presenter;
    private final ManagerControlRepairOutcome repairOutcome;

    public record Preview(Long stateId, String operationId, String message, LocalDateTime preparedAt,
                          String chatUrl) { }
    /** decision: SEEN_IN_CHAT, NOT_IN_CHAT or CLOSE; null keeps the former administrator handoff. */
    public record Request(String operationId, String note, Boolean checkedChat, String decision) {
        public Request(String operationId, String note, Boolean checkedChat) {
            this(operationId, note, checkedChat, null);
        }
    }

    @Transactional(readOnly = true)
    public Preview preview(Long cardId, Principal principal, Authentication authentication) {
        var card = eligibleCard(cardId, principal, authentication);
        var operation = messages.preview(stateId(card));
        return new Preview(operation.stateId(), operation.operationId(), operation.message(),
                operation.preparedAt(), card.getChatUrl());
    }

    @Transactional
    public ManagerControlConcreteItemResponse handoff(Long cardId, Request request,
                                                        Principal principal, Authentication authentication) {
        if (request != null && request.decision() != null && !request.decision().isBlank()) {
            return decide(cardId, request, principal, authentication);
        }
        requireAdministrator(authentication);
        if (request == null || !Boolean.TRUE.equals(request.checkedChat())
                || request.note() == null || request.note().trim().length() < 10
                || request.note().trim().length() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Откройте чат и опишите результат проверки (10–400 символов)");
        }
        var card = eligibleCard(cardId, principal, authentication);
        Long stateId = stateId(card);
        Long actorId = access.actorUserId(principal);
        var handoff = messages.handoff(stateId, request.operationId(),
                actorId, cardId, request.note());
        card.setStatus(ManagerDailyControlItemStatus.OPEN);
        card.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        card.setComment("Отправка не подтверждена, автоматический повтор запрещён. "
                + "Передано вручную: " + request.note().trim());
        card.setLastManualTouchAt(LocalDateTime.now());
        card.setFollowUpAt(null);
        cards.save(card);
        lifecycle.saveEvent(card.getControl(), card.getParentItem(), actorId,
                ManagerDailyControlEventType.ITEM_ACTION, ManagerDailyControlActionType.ACTION_TAKEN,
                "Ручная проверка WhatsApp без подтверждения отправки; карточка " + cardId
                + ", задача " + stateId + ", операция " + handoff.operationId()
                + ". Результат: " + request.note().trim());
        return presenter.concreteItemResponse(card);
    }

    private ManagerControlConcreteItemResponse decide(Long cardId, Request request,
                                                      Principal principal, Authentication authentication) {
        UncertainClientMessageDelivery.DeliveryDecision decision;
        try {
            decision = UncertainClientMessageDelivery.DeliveryDecision.valueOf(request.decision().trim());
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите, что вы увидели в чате клиента");
        }
        if (!Boolean.TRUE.equals(request.checkedChat())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Сначала откройте чат клиента и проверьте сообщение");
        }
        var card = eligibleCard(cardId, principal, authentication);
        Long stateId = stateId(card);
        Long actorId = access.actorUserId(principal);
        var outcome = messages.decide(stateId, request.operationId(), decision, actorId, cardId, request.note());
        closeOtherCardsOfTheSameTask(card, stateId, outcome.message());
        String note = request.note() == null ? "" : request.note().trim();
        String eventText = "Решение по неподтверждённой отправке (задача " + stateId + ", операция "
                + request.operationId() + "): " + decisionLabel(decision) + (note.isEmpty() ? "" : ". " + note)
                + ". Итог: " + outcome.message();
        // Every decision settles this send, «send again» included: past control days are never synchronized again,
        // so an open card would stay for weeks. If the new send fails too, the current day gets a fresh card.
        return repairOutcome.resolveRepairedConcreteItem(card, card.getControl(), outcome.message(), principal, eventText);
    }

    /**
     * Past control days are never synchronized again, so the same unconfirmed send keeps a card on every day it was
     * open. After the decision those cards describe a settled send: they close with the decision instead of offering
     * «Сверка отправки» that the server would refuse.
     */
    private void closeOtherCardsOfTheSameTask(ManagerDailyControlConcreteItem decided, Long stateId, String outcome) {
        LocalDateTime now = LocalDateTime.now();
        for (ManagerDailyControlConcreteItem other : cards.findByEntityTypeAndEntityId(ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE, stateId)) {
            if (other == null || Objects.equals(other.getId(), decided.getId())
                    || other.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
                continue;
            }
            lifecycle.recordConcreteEpisode(other, ManagerDailyControlItemStatus.RESOLVED, true);
            other.setStatus(ManagerDailyControlItemStatus.RESOLVED);
            other.setActionType(ManagerDailyControlActionType.RESOLVED);
            other.setComment(limit("Закрыто решением по этой же отправке в карточке №" + decided.getId() + ": " + outcome));
            other.setResolvedAt(now);
            other.setAutomaticResolution(true);
            other.setFollowUpAt(null);
            cards.save(other);
            lifecycle.updateParentItemFromConcreteItems(other.getParentItem());
        }
    }

    private static String limit(String text) {
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static String decisionLabel(UncertainClientMessageDelivery.DeliveryDecision decision) {
        return switch (decision) {
            case SEEN_IN_CHAT -> "сообщение есть в чате";
            case NOT_IN_CHAT -> "сообщения нет в чате, отправить снова";
            case CLOSE -> "не отправлять, клиента ведёт менеджер";
        };
    }

    private void requireAdministrator(Authentication authentication) {
        if (!permissions.hasRole(authentication, "ADMIN") && !permissions.hasRole(authentication, "OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ручная передача доступна владельцу и администратору");
        }
    }

    private ManagerDailyControlConcreteItem eligibleCard(Long cardId, Principal principal,
                                                          Authentication authentication) {
        if (cardId == null || cardId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        var card = cards.findById(cardId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка не найдена"));
        access.requireControlAccess(card.getControl(), principal, authentication);
        // An order card stays on the day's control after the order moved to another manager: only its own manager
        // (or an administrator) may decide on that order's send.
        if (ORDER_CARD_TYPES.contains(card.getEntityType())) {
            access.requireOwnOrder(card.getEntityId(), authentication);
        }
        if (card.getStatus() == ManagerDailyControlItemStatus.RESOLVED
                || !(ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(card.getEntityType())
                || ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION.equals(card.getEntityType())
                || ORDER_CARD_TYPES.contains(card.getEntityType()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Карточка не ожидает ручной сверки отправки");
        }
        stateId(card);
        return card;
    }

    /** Order cards whose order's autoresponder task may hold the unconfirmed send. */
    private static final java.util.Set<String> ORDER_CARD_TYPES = java.util.Set.of("ORDER", "WORKER_ORDER_NEW");

    private Long stateId(ManagerDailyControlConcreteItem card) {
        if (ORDER_CARD_TYPES.contains(card.getEntityType())) {
            return messages.unconfirmedStateForOrder(card.getEntityId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "У заказа нет неподтверждённой отправки автоответчика. Обновите карточку."));
        }
        var issue = failures.findIssue(card.getControl().getManager(), card.getEntityType(), card.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ошибка автоматизации уже изменилась. Обновите карточку."));
        if (!Objects.equals(issue.entityId(), card.getEntityId()) || issue.stateId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задача отправки карточки не определена");
        }
        return issue.stateId();
    }
}
