package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
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

/** A human handoff, never a statement that WhatsApp delivered the message. */
@Service
@RequiredArgsConstructor
public class ManagerControlManualDeliveryHandoffWorkflow {
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerControlAccessPolicy access;
    private final ManagerPermissionService permissions;
    private final ManagerAutomationFailureService failures;
    private final ScheduledClientMessageService messages;
    private final ManagerControlCardLifecycle lifecycle;
    private final ManagerControlConcretePresenter presenter;

    public record Preview(Long stateId, String operationId, String message, LocalDateTime preparedAt,
                          String chatUrl) { }
    public record Request(String operationId, String note, Boolean checkedChat) { }

    @Transactional(readOnly = true)
    public Preview preview(Long cardId, Principal principal, Authentication authentication) {
        var card = eligibleCard(cardId, principal, authentication);
        var operation = messages.uncertainDeliveryPreview(stateId(card));
        return new Preview(operation.stateId(), operation.operationId(), operation.message(),
                operation.preparedAt(), card.getChatUrl());
    }

    @Transactional
    public ManagerControlConcreteItemResponse handoff(Long cardId, Request request,
                                                        Principal principal, Authentication authentication) {
        if (request == null || !Boolean.TRUE.equals(request.checkedChat())
                || request.note() == null || request.note().trim().length() < 10
                || request.note().trim().length() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Откройте чат и опишите результат проверки (10–400 символов)");
        }
        var card = eligibleCard(cardId, principal, authentication);
        Long stateId = stateId(card);
        Long actorId = access.actorUserId(principal);
        var handoff = messages.handoffUncertainOrdinaryDelivery(stateId, request.operationId(),
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

    private ManagerDailyControlConcreteItem eligibleCard(Long cardId, Principal principal,
                                                          Authentication authentication) {
        if (!permissions.hasRole(authentication, "ADMIN") && !permissions.hasRole(authentication, "OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ручная передача доступна владельцу и администратору");
        }
        if (cardId == null || cardId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        var card = cards.findById(cardId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка не найдена"));
        access.requireControlAccess(card.getControl(), principal, authentication);
        if (card.getStatus() == ManagerDailyControlItemStatus.RESOLVED
                || !(ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(card.getEntityType())
                || ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION.equals(card.getEntityType()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Карточка не ожидает ручной сверки отправки");
        }
        stateId(card);
        return card;
    }

    private Long stateId(ManagerDailyControlConcreteItem card) {
        var issue = failures.findIssue(card.getControl().getManager(), card.getEntityType(), card.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ошибка автоматизации уже изменилась. Обновите карточку."));
        if (!Objects.equals(issue.entityId(), card.getEntityId()) || issue.stateId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задача отправки карточки не определена");
        }
        return issue.stateId();
    }
}
