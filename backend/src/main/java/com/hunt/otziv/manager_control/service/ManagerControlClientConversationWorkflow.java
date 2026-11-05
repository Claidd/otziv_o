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
import com.hunt.otziv.manager_control.dto.ManagerControlStaleChatInviteResponse;
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
@lombok.extern.slf4j.Slf4j
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

    /**
     * A manager closes or reviews a client message only while its chat is still assigned to them.
     * Runs in the caller's card transaction; a missing message is left to the caller's own handling.
     */
    void requireOwnMessageSource(Long unansweredItemId, Principal principal, Authentication authentication) {
        if (unansweredItemId == null || !accessPolicy.restrictedToOwnSources(authentication)) {
            return;
        }
        clientChatUnansweredItemRepository.findById(unansweredItemId)
                .ifPresent(item -> accessPolicy.requireOwnClientMessage(item, principal, authentication));
    }

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

    /** While true, only an owner or administrator closes the audit card of this message. */
    boolean ownerCheckPending(Long unansweredItemId) {
        return unansweredItemId != null && clientChatUnansweredItemRepository.findById(unansweredItemId)
                .filter(ClientChatMessageTrackerService::ownerCheckPending)
                .isPresent();
    }

    /**
     * «Ответил в другом мессенджере»: the manager answered the client outside the tracked chat. The card
     * closes and the message is not counted as ignored; the manager's comment is the claim the owner checks
     * on the «Ответ требует проверки» card (manual override, reason {@code ANSWERED_OUTSIDE_CHAT}).
     */
    @Transactional
    public ManagerControlConcreteItemResponse markAnsweredOutsideChat(
            Long concreteItemId,
            ManagerControlItemActionRequest request,
            Principal principal,
            Authentication authentication
    ) {
        ManagerDailyControlConcreteItem concreteItem = lockUnansweredCard(concreteItemId, principal, authentication);
        ManagerDailyControl control = concreteItem.getControl();
        ClientChatUnansweredItem source = clientChatUnansweredItemRepository.findByIdForUpdate(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Сообщение не найдено"));
        accessPolicy.requireOwnClientMessage(source, principal, authentication);
        if (replyOperations.findBlockingForUpdate(concreteItem.getEntityId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Исход ответа не подтвержден; сначала проверьте операцию отправки");
        }
        String comment = limit(request == null ? null : request.comment(), 1000);
        Long actorUserId = accessPolicy.actorUserId(principal);
        LocalDateTime now = LocalDateTime.now();
        clientChatMessageTrackerService.markAnsweredOutsideChat(concreteItem.getEntityId(), actorUserId, comment);
        cardLifecycle.recordConcreteEpisode(concreteItem, ManagerDailyControlItemStatus.RESOLVED, false);
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setComment(limit("Ответил вне чата: " + comment, 1000));
        concreteItem.setResolvedAt(now);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setFollowUpAt(null);
        concreteItem.setAutomaticResolution(false);
        ManagerDailyControlConcreteItem saved = dailyControlConcreteItemRepository.save(concreteItem);
        cardLifecycle.updateParentItemFromConcreteItems(saved.getParentItem());
        touchControl(control, now);
        cardLifecycle.saveEvent(control, saved.getParentItem(), actorUserId, ManagerDailyControlEventType.ITEM_RESOLVED,
                ManagerDailyControlActionType.RESOLVED,
                "Клиенту ответили вне чата, ждёт проверки владельца: " + saved.getTitle() + ". " + comment);
        return concretePresenter.concreteItemResponse(saved);
    }

    /** Chat of an unanswered card for «Ссылка-приглашение устарела»; read without holding locks. */
    @Transactional(readOnly = true)
    public ClientChatMessageTrackerService.ChatInviteContext staleChatInviteContext(
            Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        ManagerDailyControlConcreteItem concreteItem = unansweredCard(
                dailyControlConcreteItemRepository.findById(validCardId(concreteItemId)), principal, authentication);
        clientChatUnansweredItemRepository.findById(concreteItem.getEntityId())
                .ifPresent(item -> accessPolicy.requireOwnClientMessage(item, principal, authentication));
        return clientChatMessageTrackerService.chatInviteContext(concreteItem.getEntityId());
    }

    /** Read-only gateway lookup, called outside any transaction; failures mean "unknown". */
    String currentInviteLink(ClientChatMessageTrackerService.ChatInviteContext context) {
        if (context == null || !"WHATSAPP".equals(context.platform())) {
            return null;
        }
        try {
            return clientChatMessageReconciliationService
                    .currentWhatsAppInviteLink(context.whatsAppClientId(), context.chatId())
                    .orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Current WhatsApp invite was not read: itemId={}", context.itemId(), failure);
            return null;
        }
    }

    /**
     * Records the report on the card, which stays open: the client still needs an answer. The company's
     * bound group and link are not changed, the link-changed flow is not started and nothing is sent.
     */
    @Transactional
    public ManagerControlStaleChatInviteResponse recordStaleChatInvite(
            Long concreteItemId,
            ClientChatMessageTrackerService.ChatInviteContext context,
            String currentInvite,
            Principal principal,
            Authentication authentication
    ) {
        ManagerDailyControlConcreteItem concreteItem = lockUnansweredCard(concreteItemId, principal, authentication);
        ManagerDailyControl control = concreteItem.getControl();
        String message = staleChatInviteMessage(context, currentInvite);
        String inviteUrl = sameWhatsAppInvite(currentInvite, context == null ? null : context.companyChatUrl()) ? null : currentInvite;
        if (concreteItem.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            // Answered meanwhile: keep the closing comment, only return the lookup result.
            return new ManagerControlStaleChatInviteResponse(concretePresenter.concreteItemResponse(concreteItem), message, inviteUrl);
        }
        LocalDateTime now = LocalDateTime.now();
        concreteItem.setComment(limit("Ссылка-приглашение устарела. " + message, 1000));
        concreteItem.setLastManualTouchAt(now);
        ManagerDailyControlConcreteItem saved = dailyControlConcreteItemRepository.save(concreteItem);
        touchControl(control, now);
        cardLifecycle.saveEvent(control, saved.getParentItem(), accessPolicy.actorUserId(principal),
                ManagerDailyControlEventType.ITEM_ACTION, null,
                "Менеджер сообщил, что ссылка-приглашение не открывает группу: " + saved.getTitle()
                        + (inviteUrl == null ? "" : ". Подключённый аккаунт видит актуальную ссылку"));
        return new ManagerControlStaleChatInviteResponse(concretePresenter.concreteItemResponse(saved), message, inviteUrl);
    }

    static String staleChatInviteMessage(ClientChatMessageTrackerService.ChatInviteContext context, String currentInvite) {
        String platform = context == null ? "" : safe(context.platform());
        String replyHint = "Ответьте клиенту из этой карточки кнопкой «Ответить клиенту сейчас»: сообщение уйдёт в группу "
                + ("WHATSAPP".equals(platform) ? "от подключённого аккаунта компании." : "через бота компании.");
        String replaceHint = " Если ссылка в карточке компании не открывает группу, попросите новую у администратора группы и замените её в компании вручную.";
        String unchanged = " Привязка группы не менялась, никому ничего не отправлено.";
        if (!"WHATSAPP".equals(platform)) {
            String label = "TELEGRAM".equals(platform) ? "Telegram" : "MAX".equals(platform) ? "MAX" : "этого чата";
            return "Для " + label + " актуальную ссылку-приглашение автоматически не получить. " + replyHint + replaceHint + unchanged;
        }
        if (!hasText(currentInvite)) {
            return "Подключённый WhatsApp-аккаунт не смог прочитать актуальную ссылку-приглашение (WhatsApp показывает её только администраторам группы). "
                    + replyHint + replaceHint + unchanged;
        }
        if (sameWhatsAppInvite(currentInvite, context.companyChatUrl())) {
            return "Подключённый WhatsApp-аккаунт видит ту же ссылку, что указана в карточке компании. Если она не открывается, "
                    + Character.toLowerCase(replyHint.charAt(0)) + replyHint.substring(1) + replaceHint + unchanged;
        }
        return "Подключённый WhatsApp-аккаунт видит актуальную ссылку-приглашение: " + currentInvite
                + ". Откройте её, чтобы войти в группу и ответить клиенту, или " + Character.toLowerCase(replyHint.charAt(0)) + replyHint.substring(1)
                + " Ссылка в карточке компании не изменена: если старая не открывает группу, замените её в компании вручную." + unchanged;
    }

    private static final java.util.regex.Pattern WHATSAPP_INVITE_CODE =
            java.util.regex.Pattern.compile("(?i)chat\\.whatsapp\\.com/([A-Za-z0-9_-]{10,})");

    static boolean sameWhatsAppInvite(String left, String right) {
        String leftCode = whatsAppInviteCode(left);
        return leftCode != null && leftCode.equals(whatsAppInviteCode(right));
    }

    private static String whatsAppInviteCode(String link) {
        if (!hasText(link)) {
            return null;
        }
        java.util.regex.Matcher matcher = WHATSAPP_INVITE_CODE.matcher(link.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    private ManagerDailyControlConcreteItem lockUnansweredCard(Long concreteItemId, Principal principal, Authentication authentication) {
        return unansweredCard(dailyControlConcreteItemRepository.findByIdForUpdate(validCardId(concreteItemId)), principal, authentication);
    }

    private ManagerDailyControlConcreteItem unansweredCard(java.util.Optional<ManagerDailyControlConcreteItem> found,
            Principal principal, Authentication authentication) {
        ManagerDailyControlConcreteItem concreteItem = found
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        accessPolicy.requireControlAccess(concreteItem.getControl(), principal, authentication);
        if (!ENTITY_CLIENT_CHAT_UNANSWERED.equals(safe(concreteItem.getEntityType())) || concreteItem.getEntityId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Действие доступно только для неотвеченного сообщения клиента");
        }
        return concreteItem;
    }

    private static Long validCardId(Long concreteItemId) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        return concreteItemId;
    }

    private void touchControl(ManagerDailyControl control, LocalDateTime now) {
        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(cardLifecycle.recalculateControlStatus(control));
        dailyControlRepository.save(control);
    }

    private static String safe(String text) { return text == null ? "" : text.trim(); }
    private static boolean hasText(String text) { return !safe(text).isBlank(); }
    private static String limit(String text, int length) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.length() <= length ? trimmed : trimmed.substring(0, length);
    }
}
