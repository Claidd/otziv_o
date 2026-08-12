package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.dto.ClientChatUnansweredExample;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatReplyQuality;
import com.hunt.otziv.client_chat_control.model.ClientChatResolutionType;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientChatMessageTrackerService {

    private static final String ENABLED = "manager-control.unanswered-client-messages.enabled";
    private static final String WARNING_MINUTES = "manager-control.unanswered-client-messages.warning-minutes";
    private static final String EXAMPLE_LIMIT = "manager-control.unanswered-client-messages.example-limit";

    private final ClientChatMessageRepository messageRepository;
    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ClientChatParticipantClassifier participantClassifier;
    private final ClientChatAutoIgnoreService autoIgnoreService;
    private final ClientChatCompanyResolutionService companyResolutionService;
    private final AppSettingService appSettingService;
    private final GamificationEventService gamificationEventService;
    private final ClientChatIdentityService identityService;
    private final ClientChatResolutionPolicy resolutionPolicy;
    private final ClientChatReplyQualityService replyQualityService;
    private final ClientChatNoResponseAiReviewService noResponseAiReviewService;

    @Transactional
    public void track(ClientChatMessageCommand command) {
        track(command, null);
    }

    @Transactional
    public void track(ClientChatMessageCommand command, ClientChatSenderRole senderRoleOverride) {
        if (!enabled() || command == null || command.platform() == null || !hasText(command.chatId())) {
            return;
        }
        LocalDateTime messageAt = command.messageAt() == null ? LocalDateTime.now() : command.messageAt();
        String messageText = safe(command.messageText());
        if (messageText.isBlank() && hasText(command.externalMessageId())) {
            messageText = "[Нетекстовое сообщение]";
        }
        if (messageText.isBlank()) {
            return;
        }
        String externalMessageId = hasText(command.externalMessageId())
                ? limit(command.externalMessageId(), 255)
                : fallbackExternalMessageId(command, messageText, messageAt);
        if (hasText(externalMessageId)) {
            var existingMessage = messageRepository.findByPlatformAndChatIdAndExternalMessageId(
                    command.platform(),
                    limit(command.chatId(), 160),
                    externalMessageId
            );
            if (existingMessage.isPresent()) {
                reconcileExistingMessage(existingMessage.get(), command, senderRoleOverride);
                return;
            }
        }

        ClientChatCompanyResolutionService.Resolution resolution = companyResolutionService.resolve(
                command.platform(),
                command.chatId()
        );
        Company company = resolution.primaryCompany();
        Manager manager = resolution.manager();
        ClientChatDirection direction = command.direction() == null ? ClientChatDirection.INCOMING : command.direction();
        ClientChatSenderRole classifiedRole = senderRoleOverride == null
                ? participantClassifier.classify(
                        command.platform(),
                        direction,
                        command.chatId(),
                        command.senderExternalId(),
                        command.senderName(),
                        company
                )
                : senderRoleOverride;
        ClientChatSenderRole senderRole = normalizeSenderRole(command, classifiedRole);
        User actorUser = senderRole == ClientChatSenderRole.STAFF
                ? participantClassifier.resolveStaffUser(
                        command.platform(),
                        command.chatId(),
                        command.senderExternalId(),
                        command.senderName(),
                        company
                ).orElse(null)
                : null;

        ClientChatMessage message = new ClientChatMessage();
        message.setPlatform(command.platform());
        message.setDirection(direction);
        message.setSenderRole(senderRole);
        message.setChatId(limit(command.chatId(), 160));
        message.setChatTitle(limit(command.chatTitle(), 255));
        message.setExternalMessageId(externalMessageId);
        message.setCompany(company);
        message.setManager(manager);
        message.setActorUser(actorUser);
        message.setSenderExternalId(limit(command.senderExternalId(), 160));
        message.setSenderName(limit(command.senderName(), 255));
        message.setMessageText(messageText);
        message.setMatchedCompanyCount(resolution.companyCount());
        message.setMatchedCompanyTitles(limit(resolution.companyTitles(), 1000));
        message.setRoutingAmbiguous(resolution.ambiguous());
        message.setMessageAt(messageAt);
        ClientChatMessage savedMessage = messageRepository.save(message);

        if (senderRole == ClientChatSenderRole.BOT) {
            log.debug("Client chat system/outgoing message ignored for unanswered control: platform={}, chatId={}, messageId={}",
                    command.platform(), command.chatId(), savedMessage.getId());
            return;
        }
        if (senderRole == ClientChatSenderRole.STAFF) {
            if (direction == ClientChatDirection.INCOMING && isOwnerOrAdmin(actorUser)) {
                log.debug("Owner/admin chat message ignored for manager unanswered control: platform={}, chatId={}, messageId={}",
                        command.platform(), command.chatId(), savedMessage.getId());
                return;
            }
            closeOpenItems(
                    command.platform(),
                    command.chatId(),
                    ClientChatUnansweredStatus.ANSWERED,
                    "Ответ сотрудника",
                    savedMessage
            );
            resolveAuditsWithStaffReply(savedMessage);
            return;
        }
        if (direction == ClientChatDirection.OUTGOING) {
            log.debug("Client chat unclassified outgoing message ignored for unanswered control: platform={}, chatId={}, messageId={}",
                    command.platform(), command.chatId(), savedMessage.getId());
            return;
        }

        if (manager == null) {
            if (resolution.ambiguous()) {
                log.warn("Client chat message requires manual company routing: platform={}, chatId={}, companyCount={}, companies={}",
                        command.platform(), command.chatId(), resolution.companyCount(), resolution.companyTitles());
            } else {
                log.debug("Client chat message tracked without manager: platform={}, chatId={}", command.platform(), command.chatId());
            }
            return;
        }
        if (autoIgnoreService.shouldIgnore(savedMessage.getMessageText())) {
            log.debug("Client chat message auto-ignored: platform={}, chatId={}, messageId={}",
                    command.platform(), command.chatId(), savedMessage.getId());
            return;
        }
        openOrRefresh(savedMessage, company, manager);
    }

    private void reconcileExistingMessage(
            ClientChatMessage message,
            ClientChatMessageCommand command,
            ClientChatSenderRole senderRoleOverride
    ) {
        if (message == null || command == null || command.platform() == null) {
            return;
        }
        if (message.getSenderRole() == ClientChatSenderRole.STAFF
                || message.getSenderRole() == ClientChatSenderRole.BOT) {
            return;
        }
        ClientChatDirection direction = command.direction() == null
                ? ClientChatDirection.INCOMING
                : command.direction();
        ClientChatSenderRole classifiedRole = senderRoleOverride == null
                ? participantClassifier.classify(
                        command.platform(),
                        direction,
                        command.chatId(),
                        command.senderExternalId(),
                        command.senderName(),
                        message.getCompany()
                )
                : senderRoleOverride;
        ClientChatSenderRole correctedRole = normalizeSenderRole(command, classifiedRole);
        if (correctedRole != ClientChatSenderRole.STAFF && correctedRole != ClientChatSenderRole.BOT) {
            return;
        }

        message.setDirection(direction);
        message.setSenderRole(correctedRole);
        if (hasText(command.senderExternalId())) {
            message.setSenderExternalId(limit(command.senderExternalId(), 160));
        }
        if (hasText(command.senderName())) {
            message.setSenderName(limit(command.senderName(), 255));
        }
        User actorUser = correctedRole == ClientChatSenderRole.STAFF
                ? participantClassifier.resolveStaffUser(
                        command.platform(),
                        command.chatId(),
                        command.senderExternalId(),
                        command.senderName(),
                        message.getCompany()
                ).orElse(null)
                : null;
        message.setActorUser(actorUser);
        messageRepository.save(message);

        String reasonCode = correctedRole == ClientChatSenderRole.STAFF
                ? "STAFF_AUTHOR_RECLASSIFIED"
                : "SYSTEM_AUTHOR_RECLASSIFIED";
        String closeReason = correctedRole == ClientChatSenderRole.STAFF
                ? "Сообщение отправлено сотрудником"
                : "Системное сообщение";
        unansweredRepository.findByLastClientMessage(message).forEach(item -> close(
                item,
                ClientChatUnansweredStatus.MISCLASSIFIED,
                closeReason,
                ClientChatResolutionType.MISCLASSIFIED,
                message,
                reasonCode,
                "Автоматически исправлено после уточнения автора WhatsApp",
                null,
                false,
                ClientChatReplyQuality.NOT_APPLICABLE,
                "Сообщение исключено из клиентских"
        ));
    }

    private ClientChatSenderRole normalizeSenderRole(ClientChatMessageCommand command, ClientChatSenderRole senderRole) {
        return senderRole == null ? ClientChatSenderRole.UNKNOWN : senderRole;
    }

    private boolean isOwnerOrAdmin(User user) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().stream()
                .filter(java.util.Objects::nonNull)
                .map(role -> safe(role.getName()))
                .anyMatch(role -> "ROLE_OWNER".equalsIgnoreCase(role)
                        || "ROLE_ADMIN".equalsIgnoreCase(role));
    }

    @Transactional(readOnly = true)
    public long countDue(Manager manager) {
        if (!enabled() || manager == null) {
            return 0;
        }
        return unansweredRepository.countByManagerAndStatusAndLastClientMessageAtLessThanEqual(
                manager,
                ClientChatUnansweredStatus.OPEN,
                dueCutoff()
        );
    }

    @Transactional(readOnly = true)
    public List<ClientChatUnansweredExample> dueExamples(Manager manager, int requestedLimit) {
        if (!enabled() || manager == null) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(requestedLimit, appSettingService.getInt(EXAMPLE_LIMIT, 50)));
        LocalDateTime now = LocalDateTime.now();
        return unansweredRepository.findDueByManager(
                        manager,
                        ClientChatUnansweredStatus.OPEN,
                        dueCutoff(),
                        PageRequest.of(0, limit)
                ).stream()
                .map(item -> example(item, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public long countAuditRequired(Manager manager) {
        if (!enabled() || manager == null) {
            return 0;
        }
        return unansweredRepository.countByManagerAndAuditRequiredTrue(manager);
    }

    @Transactional(readOnly = true)
    public List<ClientChatUnansweredExample> auditExamples(Manager manager, int requestedLimit) {
        if (!enabled() || manager == null) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(requestedLimit, appSettingService.getInt(EXAMPLE_LIMIT, 50)));
        LocalDateTime now = LocalDateTime.now();
        return unansweredRepository.findAuditRequiredByManager(manager, PageRequest.of(0, limit)).stream()
                .map(item -> example(item, now))
                .toList();
    }

    @Transactional
    public void markFromManagerControl(Long unansweredItemId, ManagerDailyControlActionType actionType, String comment) {
        markFromManagerControl(unansweredItemId, actionType, comment, null);
    }

    @Transactional
    public void markFromManagerControl(
            Long unansweredItemId,
            ManagerDailyControlActionType actionType,
            String comment,
            Long resolvedByUserId
    ) {
        if (unansweredItemId == null) {
            return;
        }
        unansweredRepository.findById(unansweredItemId).ifPresent(item -> {
            if (item.getStatus() != ClientChatUnansweredStatus.OPEN) {
                return;
            }
            if (actionType == ManagerDailyControlActionType.DEFERRED) {
                item.setResolutionType(ClientChatResolutionType.DEFERRED);
                item.setResolutionReasonCode("FOLLOW_UP_RECORDED");
                item.setResolutionComment(limit(comment, 1000));
                item.setResolvedByUserId(resolvedByUserId);
                unansweredRepository.save(item);
                return;
            }
            assertResolutionRateAllowed(item, resolvedByUserId);
            if (actionType == ManagerDailyControlActionType.ACKNOWLEDGED) {
                markNoResponseNeeded(item, comment, resolvedByUserId);
                return;
            }
            if (actionType == ManagerDailyControlActionType.RESOLVED) {
                markActionCompletedWithEvidence(
                        item,
                        comment,
                        resolvedByUserId
                );
                return;
            }
            markAnsweredWithEvidence(item, comment, resolvedByUserId);
        });
    }

    @Transactional
    public void markConfirmedReply(
            Long unansweredItemId,
            String comment,
            Long resolvedByUserId,
            String replyText
    ) {
        ClientChatUnansweredItem item = unansweredRepository.findById(unansweredItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Неотвеченное сообщение не найдено"));
        if (item.getStatus() != ClientChatUnansweredStatus.OPEN) {
            return;
        }
        assertResolutionRateAllowed(item, resolvedByUserId);
        ClientChatReplyQualityService.Result quality = quality(item.getLastMessageText(), replyText);
        item.setResolutionReplyText(limit(replyText, 4000));
        close(
                item,
                ClientChatUnansweredStatus.ANSWERED,
                hasText(comment) ? comment : "Ответ отправлен из контроля менеджера",
                ClientChatResolutionType.ANSWERED,
                null,
                "CONFIRMED_SEND",
                comment,
                resolvedByUserId,
                false,
                quality.quality(),
                quality.reason()
        );
    }

    @Transactional
    public void markMisclassified(Long unansweredItemId, Long resolvedByUserId, String comment) {
        ClientChatUnansweredItem item = unansweredRepository.findById(unansweredItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Неотвеченное сообщение не найдено"));
        if (item.getStatus() != ClientChatUnansweredStatus.OPEN) {
            return;
        }
        ClientChatMessage message = item.getLastClientMessage();
        if (message == null
                || message.getPlatform() == null
                || !hasText(message.getChatId())
                || (!hasText(message.getSenderExternalId()) && !hasText(message.getSenderName()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Не удалось определить отправителя исходного сообщения"
            );
        }
        identityService.registerStaff(message, resolvedByUserId);
        close(
                item,
                ClientChatUnansweredStatus.MISCLASSIFIED,
                "Отправитель подтверждён как сотрудник",
                ClientChatResolutionType.MISCLASSIFIED,
                message,
                "STAFF_IDENTITY_REGISTERED",
                comment,
                resolvedByUserId,
                false,
                ClientChatReplyQuality.NOT_APPLICABLE,
                "Сообщение исключено из клиентских"
        );
    }

    @Transactional
    public void markAuditReviewed(Long unansweredItemId, Long resolvedByUserId, String comment) {
        ClientChatUnansweredItem item = unansweredRepository.findById(unansweredItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Закрытое сообщение не найдено"));
        if (!item.isAuditRequired()) {
            return;
        }
        if (!hasMeaningfulOverrideComment(comment)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Укажите, где найден ответ или какое действие выполнено после сообщения клиента"
            );
        }
        item.setAuditRequired(false);
        item.setResolutionComment(limit(comment, 1000));
        item.setResolvedByUserId(resolvedByUserId);
        unansweredRepository.save(item);
    }

    @Transactional
    public void markAuditReplySent(
            Long unansweredItemId,
            Long resolvedByUserId,
            String replyText,
            String channel
    ) {
        ClientChatUnansweredItem item = unansweredRepository.findById(unansweredItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Закрытое сообщение не найдено"));
        if (!item.isAuditRequired()) {
            return;
        }
        ClientChatReplyQualityService.Result quality = quality(item.getLastMessageText(), replyText);
        item.setResolutionReplyText(limit(replyText, 4000));
        item.setResolutionType(ClientChatResolutionType.ANSWERED);
        item.setResolutionReasonCode("AUDIT_FOLLOW_UP_SENT");
        item.setResolutionComment(limit(
                "После проверки отправлен ответ клиенту через " + safe(channel),
                1000
        ));
        item.setResolvedByUserId(resolvedByUserId);
        item.setReplyQuality(quality.quality());
        item.setReplyQualityReason(limit(quality.reason(), 500));
        item.setAuditRequired(false);
        unansweredRepository.save(item);
    }

    private void openOrRefresh(ClientChatMessage message, Company company, Manager manager) {
        ClientChatUnansweredItem item = unansweredRepository
                .findFirstByPlatformAndChatIdAndStatusOrderByLastClientMessageAtDesc(
                        message.getPlatform(),
                        message.getChatId(),
                        ClientChatUnansweredStatus.OPEN
                )
                .orElseGet(() -> {
                    ClientChatUnansweredItem created = new ClientChatUnansweredItem();
                    created.setPlatform(message.getPlatform());
                    created.setChatId(message.getChatId());
                    created.setFirstOpenedAt(message.getMessageAt());
                    created.setStatus(ClientChatUnansweredStatus.OPEN);
                    return created;
                });
        item.setChatTitle(message.getChatTitle());
        item.setCompany(company);
        item.setManager(manager);
        item.setLastClientMessage(message);
        item.setSenderExternalId(message.getSenderExternalId());
        item.setSenderName(message.getSenderName());
        item.setLastMessageText(limit(message.getMessageText(), 4000));
        item.setLastClientMessageAt(message.getMessageAt());
        item.setClosedAt(null);
        item.setCloseReason(null);
        item.setResolutionType(null);
        item.setResolutionMessage(null);
        item.setResolutionReplyText(null);
        item.setResolutionReasonCode(null);
        item.setResolutionComment(null);
        item.setResolvedByUserId(null);
        item.setManualOverride(false);
        item.setReplyQuality(null);
        item.setReplyQualityReason(null);
        item.setAuditRequired(false);
        unansweredRepository.save(item);
    }

    private void closeOpenItems(
            ClientChatPlatform platform,
            String chatId,
            ClientChatUnansweredStatus status,
            String reason,
            ClientChatMessage resolutionMessage
    ) {
        unansweredRepository.findByPlatformAndChatIdAndStatus(
                        platform,
                        limit(chatId, 160),
                        ClientChatUnansweredStatus.OPEN
                ).forEach(item -> {
                    ClientChatReplyQualityService.Result quality =
                            quality(item.getLastMessageText(), resolutionMessage == null ? null : resolutionMessage.getMessageText());
                    close(
                            item,
                            status,
                            reason,
                            ClientChatResolutionType.ANSWERED,
                            resolutionMessage,
                            "OUTGOING_STAFF_MESSAGE",
                            null,
                            null,
                            false,
                            quality.quality(),
                            quality.reason()
                    );
                });
    }

    private void resolveAuditsWithStaffReply(ClientChatMessage staffReply) {
        if (staffReply == null || staffReply.getPlatform() == null || !hasText(staffReply.getChatId())) {
            return;
        }
        unansweredRepository.findByPlatformAndChatIdAndAuditRequiredTrue(
                        staffReply.getPlatform(),
                        staffReply.getChatId()
                ).forEach(item -> {
                    if (!isReplyAfterClientMessage(item, staffReply)) {
                        return;
                    }
                    ClientChatReplyQualityService.Result quality =
                            replyQualityService.assess(item.getLastMessageText(), staffReply.getMessageText());
                    if (quality.quality() == ClientChatReplyQuality.PARTIAL
                            || quality.quality() == ClientChatReplyQuality.SUSPICIOUS) {
                        return;
                    }
                    item.setResolutionMessage(staffReply);
                    item.setResolutionReplyText(limit(staffReply.getMessageText(), 4000));
                    item.setResolutionReasonCode("AUDIT_AUTO_CLEARED_BY_FOLLOW_UP");
                    item.setResolutionComment(limit(
                            "Аудит автоматически снят: позже найден подходящий ответ сотрудника",
                            1000
                    ));
                    item.setReplyQuality(quality.quality());
                    item.setReplyQualityReason(limit(quality.reason(), 500));
                    item.setAuditRequired(false);
                    unansweredRepository.save(item);
                });
    }

    private boolean isReplyAfterClientMessage(ClientChatUnansweredItem item, ClientChatMessage staffReply) {
        if (item == null || !item.isAuditRequired()) {
            return false;
        }
        LocalDateTime clientMessageAt = item.getLastClientMessageAt();
        LocalDateTime replyAt = staffReply.getMessageAt();
        return clientMessageAt == null
                || replyAt == null
                || !replyAt.isBefore(clientMessageAt.minusMinutes(2));
    }

    private void close(ClientChatUnansweredItem item, ClientChatUnansweredStatus status, String reason) {
        close(
                item,
                status,
                reason,
                status == ClientChatUnansweredStatus.ANSWERED
                        ? ClientChatResolutionType.ANSWERED
                        : ClientChatResolutionType.NO_RESPONSE_NEEDED,
                null,
                null,
                null,
                null,
                false,
                null,
                null
        );
    }

    private void close(
            ClientChatUnansweredItem item,
            ClientChatUnansweredStatus status,
            String reason,
            ClientChatResolutionType resolutionType,
            ClientChatMessage resolutionMessage,
            String reasonCode,
            String comment,
            Long resolvedByUserId,
            boolean manualOverride,
            ClientChatReplyQuality replyQuality,
            String replyQualityReason
    ) {
        LocalDateTime closedAt = LocalDateTime.now();
        item.setStatus(status);
        item.setClosedAt(closedAt);
        item.setCloseReason(limit(reason, 255));
        item.setResolutionType(resolutionType);
        item.setResolutionMessage(resolutionMessage);
        if (resolutionMessage != null && hasText(resolutionMessage.getMessageText())) {
            item.setResolutionReplyText(limit(resolutionMessage.getMessageText(), 4000));
        }
        item.setResolutionReasonCode(limit(reasonCode, 60));
        item.setResolutionComment(limit(comment, 1000));
        item.setResolvedByUserId(resolvedByUserId);
        item.setManualOverride(manualOverride);
        item.setReplyQuality(replyQuality);
        item.setReplyQualityReason(limit(replyQualityReason, 500));
        item.setAuditRequired(
                manualOverride
                        || replyQuality == ClientChatReplyQuality.PARTIAL
                        || replyQuality == ClientChatReplyQuality.SUSPICIOUS
        );
        unansweredRepository.save(item);
        if (status == ClientChatUnansweredStatus.ANSWERED) {
            gamificationEventService.recordManagerClientReply(
                    item,
                    closedAt,
                    Math.max(1, appSettingService.getInt("manager.sla.target.message-minutes", 30)),
                    Math.max(1, appSettingService.getInt("manager.sla.hard.message-minutes", 480))
            );
        }
    }

    private ClientChatUnansweredExample example(ClientChatUnansweredItem item, LocalDateTime now) {
        Company company = item.getCompany();
        ClientChatMessage lastMessage = item.getLastClientMessage();
        boolean shared = lastMessage != null && lastMessage.getMatchedCompanyCount() > 1;
        String sharedTitle = shared
                ? "Общий чат (" + lastMessage.getMatchedCompanyCount() + "): " + safe(lastMessage.getMatchedCompanyTitles())
                : null;
        String title = hasText(sharedTitle)
                ? sharedTitle
                : hasText(item.getChatTitle()) ? item.getChatTitle() : company == null ? item.getChatId() : company.getTitle();
        return new ClientChatUnansweredExample(
                item.getId(),
                item.getPlatform(),
                company == null ? null : company.getId(),
                company == null ? null : company.getTitle(),
                item.getChatId(),
                title,
                item.getSenderName(),
                item.getLastMessageText(),
                item.getLastClientMessageAt(),
                Math.max(0, ChronoUnit.MINUTES.between(item.getLastClientMessageAt(), now)),
                targetUrl(company),
                chatUrl(item),
                specialistName(company)
        );
    }

    private LocalDateTime dueCutoff() {
        return LocalDateTime.now().minusMinutes(Math.max(0, appSettingService.getInt(WARNING_MINUTES, 0)));
    }

    private boolean enabled() {
        return appSettingService.getBoolean(ENABLED, true);
    }

    private String targetUrl(Company company) {
        if (company == null || company.getId() == null) {
            return "/companies";
        }
        return "/companies/" + company.getId();
    }

    private String chatUrl(ClientChatUnansweredItem item) {
        if (item == null || item.getPlatform() == null) {
            return null;
        }
        String companyChatUrl = normalizedChatUrl(item.getCompany() == null ? null : item.getCompany().getUrlChat());
        if (hasText(companyChatUrl)) {
            return companyChatUrl;
        }
        if (item.getPlatform() == ClientChatPlatform.TELEGRAM) {
            return telegramChatUrl(item.getChatId());
        }
        if (item.getPlatform() == ClientChatPlatform.MAX) {
            return "https://max.ru/";
        }
        return null;
    }

    private String specialistName(Company company) {
        if (company == null || company.getWorkers() == null || company.getWorkers().isEmpty()) {
            return "Исполнитель не назначен";
        }
        return company.getWorkers().stream()
                .map(this::workerName)
                .filter(ClientChatMessageTrackerService::hasText)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .findFirst()
                .orElse("Исполнитель не назначен");
    }

    private String workerName(Worker worker) {
        User user = worker == null ? null : worker.getUser();
        String fio = safe(user == null ? null : user.getFio());
        if (hasText(fio)) {
            return fio;
        }
        return safe(user == null ? null : user.getUsername());
    }

    private String normalizedChatUrl(String value) {
        String url = safe(value);
        if (!hasText(url)) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "https://" + url;
    }

    private String telegramChatUrl(String chatId) {
        String value = safe(chatId);
        if (!hasText(value)) {
            return null;
        }
        if (value.startsWith("-100") && value.length() > 4) {
            return "https://t.me/c/" + encode(value.substring(4));
        }
        return "https://t.me/c/" + encode(value.replaceFirst("^-", ""));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String fallbackExternalMessageId(
            ClientChatMessageCommand command,
            String messageText,
            LocalDateTime messageAt
    ) {
        String fingerprintSource = command.platform() + "|"
                + safe(command.chatId()) + "|"
                + safe(command.senderExternalId()) + "|"
                + (command.direction() == null ? ClientChatDirection.INCOMING : command.direction()) + "|"
                + messageAt.truncatedTo(ChronoUnit.SECONDS) + "|"
                + messageText;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fingerprintSource.getBytes(StandardCharsets.UTF_8));
            return "fp:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void markNoResponseNeeded(
            ClientChatUnansweredItem item,
            String comment,
            Long resolvedByUserId
    ) {
        ClientChatResolutionPolicy.Assessment assessment = resolutionPolicy.assess(item.getLastMessageText());
        ClientChatNoResponseAiReviewService.Review aiReview =
                noResponseAiReviewService.review(item.getLastMessageText());
        if (!aiReview.checked()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DeepSeek временно не смог проверить сообщение. Карточка остаётся открытой"
            );
        }
        if (!aiReview.confirmed()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "DeepSeek не подтвердил, что ответ не требуется: " + aiReview.reason()
                            + ". Карточка остаётся открытой"
            );
        }
        if (hardNoResponseRejection(assessment)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сообщение похоже на вопрос, проблему или поручение. Даже после проверки DeepSeek карточка останется открытой до подтвержденного ответа"
            );
        }
        String aiAudit = "DeepSeek подтвердил, что ответ не требуется ("
                + aiReview.confidence() + "%): " + aiReview.reason();
        String auditComment = hasText(comment)
                ? limit(comment + "\n" + aiAudit, 1000)
                : limit(aiAudit, 1000);
        close(
                item,
                ClientChatUnansweredStatus.NO_RESPONSE_NEEDED,
                "DeepSeek подтвердил: сообщение клиента не требует ответа",
                ClientChatResolutionType.NO_RESPONSE_NEEDED,
                null,
                "DEEPSEEK_NO_RESPONSE_CONFIRMED",
                auditComment,
                resolvedByUserId,
                false,
                ClientChatReplyQuality.NOT_APPLICABLE,
                limit(aiAudit + "; правило: " + assessment.reasonCode(), 500)
        );
    }

    private boolean hardNoResponseRejection(ClientChatResolutionPolicy.Assessment assessment) {
        if (assessment == null || assessment.reasonCode() == null) {
            return true;
        }
        return switch (assessment.reasonCode()) {
            case "QUESTION", "PROBLEM_OR_COMPLAINT", "ACTION_REQUEST",
                    "ATTACHMENT_REQUIRES_REVIEW", "EMPTY" -> true;
            default -> false;
        };
    }

    private void markAnsweredWithEvidence(
            ClientChatUnansweredItem item,
            String comment,
            Long resolvedByUserId
    ) {
        ClientChatMessage evidence = staffReplyEvidence(item);
        if (evidence == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Исходящий ответ после сообщения клиента не найден. Ответьте из карточки или откройте чат и дождитесь синхронизации"
            );
        }
        ClientChatReplyQualityService.Result quality =
                quality(item.getLastMessageText(), evidence.getMessageText());
        close(
                item,
                ClientChatUnansweredStatus.ANSWERED,
                "Ответ сотрудника найден",
                ClientChatResolutionType.ANSWERED,
                evidence,
                "OUTGOING_STAFF_MESSAGE",
                comment,
                resolvedByUserId,
                false,
                quality.quality(),
                quality.reason()
        );
    }

    private void markActionCompletedWithEvidence(
            ClientChatUnansweredItem item,
            String comment,
            Long resolvedByUserId
    ) {
        ClientChatMessage evidence = staffReplyEvidence(item);
        if (evidence != null) {
            ClientChatReplyQualityService.Result quality =
                    quality(item.getLastMessageText(), evidence.getMessageText());
            close(
                    item,
                    ClientChatUnansweredStatus.ANSWERED,
                    "Ответ сотрудника найден",
                    ClientChatResolutionType.ANSWERED,
                    evidence,
                    "OUTGOING_STAFF_MESSAGE",
                    comment,
                    resolvedByUserId,
                    false,
                    quality.quality(),
                    quality.reason()
            );
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Исходящий ответ после сообщения клиента не найден. Ответьте клиенту или нажмите «Проверить ответ»; карточка останется открытой"
        );
    }

    private ClientChatMessage staffReplyEvidence(ClientChatUnansweredItem item) {
        if (item == null || item.getPlatform() == null || !hasText(item.getChatId())) {
            return null;
        }
        LocalDateTime after = item.getLastClientMessageAt() == null
                ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : item.getLastClientMessageAt();
        return messageRepository
                .findFirstByPlatformAndChatIdAndSenderRoleAndMessageAtAfterOrderByMessageAtAscIdAsc(
                        item.getPlatform(),
                        item.getChatId(),
                        ClientChatSenderRole.STAFF,
                        after
                )
                .orElse(null);
    }

    private void assertResolutionRateAllowed(ClientChatUnansweredItem item, Long resolvedByUserId) {
        if (!appSettingService.getBoolean(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_GUARD_ENABLED,
                false
        ) || item.getManager() == null || resolvedByUserId == null) {
            return;
        }
        int warningSeconds = Math.max(3, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_WARNING_SECONDS,
                10
        ));
        int warningCount = Math.max(3, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_WARNING_COUNT,
                3
        ));
        int criticalSeconds = Math.max(warningSeconds, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_CRITICAL_SECONDS,
                60
        ));
        int criticalCount = Math.max(warningCount, appSettingService.getInt(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_FAST_CLICK_CRITICAL_COUNT,
                10
        ));
        long warningWindowClosures = unansweredRepository.countByManagerAndResolvedByUserIdAndClosedAtAfter(
                item.getManager(),
                resolvedByUserId,
                LocalDateTime.now().minusSeconds(warningSeconds)
        );
        long criticalWindowClosures = unansweredRepository.countByManagerAndResolvedByUserIdAndClosedAtAfter(
                item.getManager(),
                resolvedByUserId,
                LocalDateTime.now().minusSeconds(criticalSeconds)
        );
        if (warningWindowClosures >= warningCount - 1L || criticalWindowClosures >= criticalCount - 1L) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Слишком много быстрых закрытий. Перечитайте сообщение и повторите действие через несколько секунд"
            );
        }
    }

    private ClientChatReplyQualityService.Result quality(String clientMessage, String replyText) {
        if (!appSettingService.getBoolean(
                AppSettingService.MANAGER_CONTROL_UNANSWERED_REPLY_QUALITY_SHADOW_ENABLED,
                true
        )) {
            return new ClientChatReplyQualityService.Result(
                    ClientChatReplyQuality.NOT_APPLICABLE,
                    "Теневая проверка качества выключена"
            );
        }
        return replyQualityService.assess(clientMessage, replyText);
    }

    private boolean hasMeaningfulOverrideComment(String comment) {
        String value = safe(comment);
        String normalized = value.replaceFirst("[\\p{P}\\s]+$", "");
        return normalized.length() >= 10
                && !"Ответ клиенту проверен вручную".equalsIgnoreCase(normalized)
                && !"Сообщение клиента не требует ответа".equalsIgnoreCase(normalized);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
