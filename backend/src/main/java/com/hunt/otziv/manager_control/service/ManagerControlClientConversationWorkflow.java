package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.dto.ClientChatReconciliationResult;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageReconciliationService;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_chat_control.service.ClientChatReplySuggestionService;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplySuggestionResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.LocalDateTime;
import com.hunt.otziv.manager_control.repository.ManagerClientReplyOperationRepository;

/** Client conversation queries and operator classification, separate from delivery. */
@Service
@RequiredArgsConstructor
public class ManagerControlClientConversationWorkflow {
    private static final String ENTITY_CLIENT_CHAT_UNANSWERED = "CLIENT_CHAT_UNANSWERED";
    private static final String ENTITY_CLIENT_CHAT_AUDIT = "CLIENT_CHAT_AUDIT";
    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    private final ManagerDailyControlRepository dailyControlRepository;
    private final ClientChatUnansweredItemRepository clientChatUnansweredItemRepository;
    private final ClientChatMessageTrackerService clientChatMessageTrackerService;
    private final ClientChatReplySuggestionService clientChatReplySuggestionService;
    private final ClientChatMessageReconciliationService clientChatMessageReconciliationService;
    private final ManagerClientReplyOperationRepository replyOperations;
    private final ManagerControlAccessPolicy accessPolicy;
    private final ManagerControlCardLifecycle cardLifecycle;
    private final ManagerControlConcretePresenter concretePresenter;

    @Transactional(readOnly = true)
    public ManagerControlClientReplySuggestionResponse suggestClientReply(
            Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        accessPolicy.requireControlAccess(concreteItem.getControl(), principal, authentication);
        if ((!ENTITY_CLIENT_CHAT_UNANSWERED.equals(safe(concreteItem.getEntityType()))
                && !ENTITY_CLIENT_CHAT_AUDIT.equals(safe(concreteItem.getEntityType())))
                || concreteItem.getEntityId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Подсказка доступна только для клиентского сообщения"
            );
        }
        ClientChatUnansweredItem item = clientChatUnansweredItemRepository.findById(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Неотвеченное сообщение не найдено"));
        accessPolicy.requireClientMessageAccess(item, concreteItem.getControl(), principal, authentication);
        ClientChatReplySuggestionService.Suggestion suggestion =
                clientChatReplySuggestionService.suggest(item.getLastMessageText());
        return new ManagerControlClientReplySuggestionResponse(
                suggestion.message(),
                suggestion.reasonCode()
        );
    }

    @Transactional
    public ManagerControlConcreteItemResponse markClientMessageMisclassified(
            Long concreteItemId,
            ManagerControlItemActionRequest request,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findByIdForUpdate(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        accessPolicy.requireControlAccess(control, principal, authentication);
        if (!ENTITY_CLIENT_CHAT_UNANSWERED.equals(safe(concreteItem.getEntityType()))
                || concreteItem.getEntityId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Исправление отправителя доступно только для клиентских сообщений"
            );
        }

        ClientChatUnansweredItem source = clientChatUnansweredItemRepository.findByIdForUpdate(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Сообщение не найдено"));
        accessPolicy.requireClientMessageAccess(source, control, principal, authentication);
        if (replyOperations.findBlockingForUpdate(concreteItem.getEntityId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Исход ответа не подтвержден; сначала проверьте операцию отправки");
        }
        String comment = limit(request == null ? null : request.comment(), 1000);
        LocalDateTime now = LocalDateTime.now();
        clientChatMessageTrackerService.markMisclassified(
                concreteItem.getEntityId(),
                accessPolicy.actorUserId(principal),
                comment
        );
        cardLifecycle.recordConcreteEpisode(concreteItem, ManagerDailyControlItemStatus.RESOLVED, false);
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setComment(hasText(comment) ? comment : "Отправитель подтверждён как сотрудник");
        concreteItem.setResolvedAt(now);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setFollowUpAt(null);
        concreteItem.setAutomaticResolution(false);
        ManagerDailyControlConcreteItem saved = dailyControlConcreteItemRepository.save(concreteItem);
        cardLifecycle.updateParentItemFromConcreteItems(saved.getParentItem());

        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(cardLifecycle.recalculateControlStatus(control));
        dailyControlRepository.save(control);
        cardLifecycle.saveEvent(
                control,
                saved.getParentItem(),
                accessPolicy.actorUserId(principal),
                ManagerDailyControlEventType.ITEM_RESOLVED,
                ManagerDailyControlActionType.RESOLVED,
                "Исправлена роль отправителя клиентского сообщения: " + saved.getTitle()
        );
        return concretePresenter.concreteItemResponse(saved);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ClientChatReconciliationResult reconcileClientMessages(
            Long managerId,
            Principal principal,
            Authentication authentication
    ) {
        if (managerId == null || managerId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный менеджер");
        }
        Manager manager = accessPolicy.visibleManagers(principal, authentication).stream()
                .filter(item -> managerId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер недоступен"));
        return clientChatMessageReconciliationService.reconcileOpenWhatsAppMessages(manager);
    }
    private static String safe(String text) { return text == null ? "" : text.trim(); }
    private static boolean hasText(String text) { return !safe(text).isBlank(); }
    private static String limit(String text, int length) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.length() <= length ? trimmed : trimmed.substring(0, length);
    }
}
