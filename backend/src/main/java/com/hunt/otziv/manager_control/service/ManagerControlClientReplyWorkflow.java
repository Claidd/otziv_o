package com.hunt.otziv.manager_control.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.manager_control.dto.ManagerClientReplyResolutionRequest;
import com.hunt.otziv.manager_control.dto.ManagerClientReplyResolutionResponse;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope;
import com.hunt.otziv.whatsapp.dto.WhatsAppOperationStatus;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;

import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplyRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.*;
import com.hunt.otziv.manager_control.repository.ManagerClientReplyOperationRepository;
import com.hunt.otziv.manager_control.repository.ManagerClientReplyOperationRepository.Operation;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** One operator reply, fenced across daily cards and process restarts. UNKNOWN is never resent. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlClientReplyWorkflow {
    private static final ObjectMapper SNAPSHOTS = new ObjectMapper().findAndRegisterModules();
    private static final String PREPARED = "client_reply_delivery_prepared:";
    private static final String UNKNOWN = "client_reply_delivery_unknown:";
    private final ManagerControlTransactionRunner transactions;
    private final ManagerDailyControlConcreteItemRepository cards;
    private final ManagerDailyControlRepository controls;
    private final ClientChatUnansweredItemRepository unanswered;
    private final ManagerClientReplyOperationRepository operations;
    private final ClientChatMessageSender sender;
    private final WhatsAppService whatsapp;
    private final ClientChatMessageTrackerService tracker;
    private final ManagerControlAccessPolicy access;
    private final ManagerControlCardLifecycle lifecycle;
    private final ManagerControlConcretePresenter presenter;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManagerControlConcreteItemResponse reply(Long cardId, ManagerControlClientReplyRequest request,
                                                  Principal principal, Authentication authentication) {
        String text = request == null || request.message() == null ? "" : request.message().trim();
        if (text.isBlank() || text.length() > 4000) throw badRequest("Введите ответ клиенту длиной от 1 до 4000 символов");
        Preparation preparation = transactions.required(() -> prepare(cardId, text, principal, authentication));
        // Rejections after a stale preparation commit its UNKNOWN state before returning a conflict.
        if (preparation.rejection() != null) throw conflict(preparation.rejection());
        Prepared reply = preparation.reply();
        ClientMessageSendResult result;
        try {
            Source source = reply.source();
            result = sender.sendToPlatformWithOperationId(source.platform(), source.company().toMessageCompany(),
                    source.clientId(), source.chatId(), source.chatId(), reply.message(), reply.operation().token());
        } catch (Exception unconfirmed) {
            finishFailure(reply, true, "sender_exception");
            throw conflict("Исход ответа не подтвержден; проверьте операцию " + reply.operation().token());
        }
        if (result == null || !result.sent() || result.messageId() == null || result.messageId().isBlank() || result.messageId().length() > 512) {
            boolean unknown = !ClientChatMessageSender.isKnownUnsent(result);
            finishFailure(reply, unknown, safeCode(result == null ? null : result.errorCode()));
            throw conflict((unknown ? "Исход ответа не подтвержден; проверьте операцию "
                    : "Ответ достоверно не отправлен; повтор доступен для операции ") + reply.operation().token());
        }
        try {
            return transactions.required(() -> finishSuccess(reply, result, principal, authentication));
        } catch (RuntimeException failedFinalization) {
            finishFailure(reply, true, "finalization_failed");
            throw conflict("Ответ доставлен, но карточка изменилась; проверьте операцию " + reply.operation().token());
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManagerClientReplyResolutionResponse reconcile(Long cardId, String token,
            ManagerClientReplyResolutionRequest request,
            Principal principal, Authentication authentication) {
        if (token == null || !token.matches("[0-9a-f-]{36}")) throw badRequest("Некорректная операция");
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > 1000) throw badRequest("Укажите причину сверки длиной до 1000 символов");
        Prepared reply = transactions.required(() -> recoveryCandidate(cardId, token, principal, authentication));
        if ("SUCCEEDED".equals(reply.operation().state())) return resolution(reply.operation());
        var captured = reply.source();
        if (captured.platform() != ClientChatPlatform.WHATSAPP)
            throw conflict("Канал не предоставляет проверяемое подтверждение операции; сохранён карантин для ручной сверки");
        WhatsAppOperationStatus proof;
        try { proof = whatsapp.getOperationStatus(captured.clientId(), token); }
        catch (RuntimeException unavailable) { throw conflict("Подтверждение провайдера недоступно; карантин сохранён"); }
        String expectedHash = WhatsAppOperationEnvelope.groupHash(
                captured.clientId(), captured.chatId(), reply.message());
        if (proof == null || !token.equals(proof.operationId()) || !"SUCCEEDED".equals(proof.state())
                || proof.messageId() == null || proof.messageId().isBlank() || proof.messageId().length() > 512
                || !expectedHash.equals(proof.envelopeHash()))
            throw conflict("Доставка исходного сообщения не подтверждена; карантин сохранён");
        return transactions.required(() -> {
            var card = lockedCard(cardId, principal, authentication);
            var item = lockedSource(card, principal, authentication);
            Operation current = operations.findTokenForUpdate(token).orElseThrow(() -> conflict("Операция не найдена"));
            Prepared persisted = recoverySnapshot(current, card, principal, authentication);
            if ("SUCCEEDED".equals(current.state())) return resolution(current);
            if (!"UNKNOWN".equals(current.state()) || !Objects.equals(current.snapshot(), reply.operation().snapshot()))
                throw conflict("Состояние операции изменилось");
            boolean ownsCard = (UNKNOWN + token).equals(card.getComment()) || (PREPARED + token).equals(card.getComment());
            boolean sourceMatches = persisted.source().equals(source(card, item));
            boolean apply = ownsCard && sourceMatches;
            if (apply) {
                requireOpenSource(persisted.source());
                applySuccess(persisted, ClientMessageSendResult.sent("WhatsApp"), card, item);
            } else if (ownsCard) {
                // Delivery of the old inbound must never close a newer message on the reused source.
                persisted.previous().restore(card);
                cards.save(card);
            }
            Long resolverId = access.actorUserId(principal);
            operations.confirmDelivery(current, proof.messageId(), apply, resolverId, reason);
            lifecycle.saveEvent(card.getControl(), card.getParentItem(), resolverId, ManagerDailyControlEventType.ITEM_ACTION,
                    ManagerDailyControlActionType.ACTION_TAKEN, "Подтверждена операция ответа " + token
                            + (apply ? "; исходная карточка обновлена" : "; текущий источник сохранён без закрытия"));
            return new ManagerClientReplyResolutionResponse(token, "SUCCEEDED", apply, proof.messageId());
        });
    }

    private Prepared recoveryCandidate(Long cardId, String token, Principal principal, Authentication authentication) {
        var card = lockedCard(cardId, principal, authentication);
        lockedSource(card, principal, authentication);
        Operation operation = operations.findTokenForUpdate(token).orElseThrow(() -> conflict("Операция не найдена"));
        Prepared reply = recoverySnapshot(operation, card, principal, authentication);
        if ("PREPARED".equals(operation.state())) {
            if (!operation.preparedAt().isBefore(LocalDateTime.now().minusMinutes(15)))
                throw conflict("Отправка ещё выполняется; повторная отправка запрещена");
            operations.finish(operation, "UNKNOWN", null, "stale_preparation");
            if ((PREPARED + token).equals(card.getComment())) { card.setComment(UNKNOWN + token); cards.save(card); }
        } else if (!"UNKNOWN".equals(operation.state()) && !"SUCCEEDED".equals(operation.state())) {
            throw conflict("Операция достоверно не отправлена; сверка доставки неприменима");
        }
        return reply;
    }

    private Prepared recoverySnapshot(Operation operation, ManagerDailyControlConcreteItem card,
                                      Principal principal, Authentication authentication) {
        if (!Objects.equals(operation.cardId(), card.getId()) || !Objects.equals(operation.itemId(), card.getEntityId()))
            throw conflict("Операция относится к другой карточке");
        Prepared reply = decode(operation);
        if (!Objects.equals(reply.controlId(), card.getControl().getId())) throw conflict("Контроль операции изменился");
        access.requireCurrentCompanyAccess(reply.source().company().id(), authentication);
        Manager capturedManager = new Manager(); capturedManager.setId(reply.source().managerId());
        access.requireManagerAccess(capturedManager, principal, authentication);
        return reply;
    }

    private static ManagerClientReplyResolutionResponse resolution(Operation operation) {
        return new ManagerClientReplyResolutionResponse(
                operation.token(), operation.state(), operation.sourceApplied(), operation.messageId());
    }

    private Preparation prepare(Long cardId, String message, Principal principal, Authentication authentication) {
        ManagerDailyControlConcreteItem card = lockedCard(cardId, principal, authentication);
        ClientChatUnansweredItem item = lockedSource(card, principal, authentication);
        var blocking = operations.findBlockingForUpdate(item.getId());
        if (blocking.isPresent()) {
            Operation operation = blocking.get();
            if ("PREPARED".equals(operation.state()) && operation.preparedAt().isBefore(LocalDateTime.now().minusMinutes(15))) {
                operations.finish(operation, "UNKNOWN", null, "stale_preparation");
                if (Objects.equals(card.getId(), operation.cardId()) && (PREPARED + operation.token()).equals(card.getComment())) {
                    card.setComment(UNKNOWN + operation.token());
                    cards.save(card);
                }
            }
            return new Preparation(null, "Предыдущий ответ требует сверки; операция " + operation.token());
        }
        Source source = source(card, item);
        String requestHash = requestHash(source, message);
        var existing = operations.findRequestForUpdate(item.getId(), requestHash);
        if (existing.isPresent() && !"FAILED_KNOWN".equals(existing.get().state())) {
            return new Preparation(null, "Этот ответ уже обработан; операция " + existing.get().token());
        }
        requireOpenSource(source);
        if (card.getStatus() == ManagerDailyControlItemStatus.RESOLVED) throw conflict("Карточка уже закрыта");
        PreviousCard previous = PreviousCard.capture(card);
        LocalDateTime now = LocalDateTime.now();
        Long actorId = access.actorUserId(principal);
        Snapshot snapshot = new Snapshot(source, message, previous, actorId, card.getControl().getId());
        String serialized = encode(snapshot);
        Operation operation = existing.isPresent() ? operations.retryKnownUnsent(existing.get(), card.getId(), now, serialized)
                : operations.create(item.getId(), card.getId(), requestHash, now, serialized);
        card.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        card.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        card.setLastManualTouchAt(now);
        card.setComment(PREPARED + operation.token());
        cards.save(card);
        return new Preparation(new Prepared(operation, source, message, previous, actorId, card.getControl().getId()), null);
    }

    private ManagerControlConcreteItemResponse finishSuccess(Prepared reply, ClientMessageSendResult result,
                                                            Principal principal, Authentication authentication) {
        ManagerDailyControlConcreteItem card = lockedCard(reply.operation().cardId(), principal, authentication);
        ClientChatUnansweredItem item = lockedSource(card, principal, authentication);
        Operation current = operations.findTokenForUpdate(reply.operation().token()).orElseThrow(() -> conflict("Операция не найдена"));
        requirePrepared(reply, current, card);
        Source source = source(card, item);
        if (!source.equals(reply.source())) throw conflict("Сообщение или назначение менеджера изменилось во время ответа");
        requireOpenSource(source);
        ManagerControlConcreteItemResponse response = applySuccess(reply, result, card, item);
        operations.finish(current, "SUCCEEDED", result.channel(), null, result.messageId());
        return response;
    }

    private ManagerControlConcreteItemResponse applySuccess(Prepared reply, ClientMessageSendResult result,
                                                           ManagerDailyControlConcreteItem card, ClientChatUnansweredItem item) {
        Source source = reply.source();
        LocalDateTime now = LocalDateTime.now();
        ManagerDailyControl control = card.getControl();
        ManagerDailyControlItemStatus status = source.audit() ? ManagerDailyControlItemStatus.RESOLVED : ManagerDailyControlItemStatus.ACTION_TAKEN;
        ManagerDailyControlActionType action = source.audit() ? ManagerDailyControlActionType.RESOLVED : ManagerDailyControlActionType.ACTION_TAKEN;
        // Count the original OPEN episode, not the temporary PREPARED fence.
        card.setStatus(reply.previous().status());
        lifecycle.recordConcreteEpisode(card, status, false);
        card.setStatus(status); card.setActionType(action); card.setLastManualTouchAt(now); card.setResolvedAt(now);
        card.setAutomaticResolution(false); card.setFollowUpAt(null);
        card.setComment(limit("Ответ отправлен через " + result.channel() + ": " + reply.message(), 1000));
        ManagerDailyControlConcreteItem saved = cards.save(card);
        if (source.audit()) tracker.markAuditReplySent(item.getId(), reply.actorId(), reply.message(), result.channel());
        else tracker.markConfirmedReply(item.getId(), "Ответ отправлен из контроля менеджера через " + result.channel(),
                reply.actorId(), reply.message());
        lifecycle.updateParentItemFromConcreteItems(saved.getParentItem());
        if (control.getStartedAt() == null) control.setStartedAt(now);
        control.setLastActivityAt(now); control.setStatus(lifecycle.recalculateControlStatus(control)); controls.save(control);
        lifecycle.saveEvent(control, saved.getParentItem(), reply.actorId(), ManagerDailyControlEventType.ITEM_ACTION,
                action, "Ответ клиенту отправлен из карточки: " + card.getTitle() + " через " + result.channel());
        return presenter.concreteItemResponse(saved, reply.message());
    }

    private void finishFailure(Prepared reply, boolean unknown, String errorCode) {
        try {
            transactions.required(() -> {
                // Same lock order as preparation/finalization: card, source, then operation.
                var card = cards.findByIdForUpdate(reply.operation().cardId()).orElse(null);
                unanswered.findByIdForUpdate(reply.operation().itemId());
                Operation current = operations.findTokenForUpdate(reply.operation().token()).orElseThrow();
                if (!"PREPARED".equals(current.state()) || !current.requestHash().equals(reply.operation().requestHash())) return null;
                boolean ownsCard = card != null && (PREPARED + current.token()).equals(card.getComment());
                // A modified card never proves an operation was definitely unsent.
                boolean quarantine = unknown || !ownsCard;
                operations.finish(current, quarantine ? "UNKNOWN" : "FAILED_KNOWN", null, errorCode);
                if (ownsCard) {
                    if (quarantine) card.setComment(UNKNOWN + current.token());
                    else reply.previous().restore(card);
                    cards.save(card);
                }
                return null;
            });
        } catch (RuntimeException failedWrite) {
            // PREPARED remains a durable blocking state after a database outage; never infer failure/retry.
            log.error("Cannot finalize manager reply operation {}", reply.operation().token());
        }
    }

    private ManagerDailyControlConcreteItem lockedCard(Long id, Principal principal, Authentication authentication) {
        if (id == null || id <= 0) throw badRequest("Некорректная карточка контроля");
        var card = cards.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка не найдена"));
        access.requireControlAccess(card.getControl(), principal, authentication);
        if ((!"CLIENT_CHAT_UNANSWERED".equals(card.getEntityType()) && !"CLIENT_CHAT_AUDIT".equals(card.getEntityType())) || card.getEntityId() == null)
            throw badRequest("Ответ доступен только для клиентского сообщения");
        return card;
    }

    private ClientChatUnansweredItem lockedSource(ManagerDailyControlConcreteItem card, Principal principal, Authentication authentication) {
        var item = unanswered.findByIdForUpdate(card.getEntityId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Сообщение не найдено"));
        access.requireClientMessageAccess(item, card.getControl(), principal, authentication);
        return item;
    }

    private Source source(ManagerDailyControlConcreteItem card, ClientChatUnansweredItem item) {
        Manager manager = item.getManager() == null ? card.getControl().getManager() : item.getManager();
        if (item.getPlatform() == null || item.getChatId() == null || item.getChatId().isBlank() || item.getLastClientMessageAt() == null)
            throw badRequest("Источник сообщения не содержит данных для отправки");
        return new Source(item.getId(), item.getPlatform(), item.getChatId(), manager == null ? null : manager.getId(),
                manager == null ? null : manager.getClientId(), ManagerControlMessageCompany.capture(item.getCompany()),
                item.getLastClientMessage() == null ? null : item.getLastClientMessage().getId(), item.getLastClientMessageAt(),
                "CLIENT_CHAT_AUDIT".equals(card.getEntityType()), item.getStatus(), item.isAuditRequired());
    }

    private void requireOpenSource(Source source) {
        if (source.audit() ? !source.auditRequired() : source.status() != ClientChatUnansweredStatus.OPEN)
            throw conflict("Сообщение или проверка уже закрыты");
    }

    private void requirePrepared(Prepared expected, Operation actual, ManagerDailyControlConcreteItem card) {
        if (!"PREPARED".equals(actual.state()) || !Objects.equals(actual.cardId(), card.getId())
                || !Objects.equals(expected.controlId(), card.getControl().getId())
                || !actual.requestHash().equals(expected.operation().requestHash())
                || !(PREPARED + actual.token()).equals(card.getComment())) throw conflict("Состояние операции изменилось");
    }

    private String requestHash(Source source, String message) {
        try {
            // Length-prefixed fields avoid delimiter collisions; hashes contain no plaintext message.
            StringBuilder canonical = new StringBuilder("manager-reply-v1");
            Object[] fields = {source.itemId(), source.platform(), source.chatId(), source.managerId(), source.clientId(),
                    source.company().id(), source.company().telegramGroupChatId(), source.company().maxGroupChatId(),
                    source.messageId(), source.messageAt(), source.audit(), message};
            for (Object field : fields) { String value = field == null ? "" : field.toString(); canonical.append('|').append(value.length()).append(':').append(value); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String encode(Snapshot snapshot) {
        try { return SNAPSHOTS.writeValueAsString(snapshot); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Reply snapshot cannot be persisted", invalid); }
    }

    private Prepared decode(Operation operation) {
        try {
            Snapshot snapshot = SNAPSHOTS.readValue(operation.snapshot(), Snapshot.class);
            if (snapshot == null || snapshot.source() == null || snapshot.previous() == null
                    || snapshot.message() == null || snapshot.message().isBlank() || snapshot.message().length() > 4000
                    || !Objects.equals(operation.itemId(), snapshot.source().itemId())
                    || !operation.requestHash().equals(requestHash(snapshot.source(), snapshot.message())))
                throw new IllegalStateException("Invalid reply snapshot");
            return new Prepared(operation, snapshot.source(), snapshot.message(), snapshot.previous(), snapshot.actorId(), snapshot.controlId());
        } catch (java.io.IOException | IllegalArgumentException invalid) { throw new IllegalStateException("Reply snapshot cannot be read", invalid); }
    }

    private static String safeCode(String code) { return code != null && code.matches("[A-Za-z0-9_:-]{1,100}") ? code : "unconfirmed_result"; }
    private static String limit(String text, int length) { return text.length() <= length ? text : text.substring(0, length); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Preparation(Prepared reply, String rejection) { }
    private record Prepared(Operation operation, Source source, String message, PreviousCard previous, Long actorId, Long controlId) { }
    public record Snapshot(Source source, String message, PreviousCard previous, Long actorId, Long controlId) { }
    public record Source(Long itemId, ClientChatPlatform platform, String chatId, Long managerId, String clientId,
                          ManagerControlMessageCompany company, Long messageId, LocalDateTime messageAt,
                          boolean audit, ClientChatUnansweredStatus status, boolean auditRequired) { }
    public record PreviousCard(ManagerDailyControlItemStatus status, ManagerDailyControlActionType action, String comment,
                                LocalDateTime touched, LocalDateTime followUp, LocalDateTime resolved, boolean automatic) {
        static PreviousCard capture(ManagerDailyControlConcreteItem card) {
            return new PreviousCard(card.getStatus(), card.getActionType(), card.getComment(), card.getLastManualTouchAt(),
                    card.getFollowUpAt(), card.getResolvedAt(), card.isAutomaticResolution());
        }
        void restore(ManagerDailyControlConcreteItem card) {
            card.setStatus(status); card.setActionType(action); card.setComment(comment); card.setLastManualTouchAt(touched);
            card.setFollowUpAt(followUp); card.setResolvedAt(resolved); card.setAutomaticResolution(automatic);
        }
    }
}
