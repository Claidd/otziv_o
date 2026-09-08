package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEventType;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.payments.service.BadReviewPaymentInstructionOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import lombok.extern.slf4j.Slf4j;

/** Durable prepare/deliver/finalize orchestration; provider I/O never joins the caller transaction. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManagerControlClientSendWorkflow {
    private static final Duration CLIENT_MESSAGE_PREPARED_STALE_AFTER = Duration.ofMinutes(15);
    private static final String CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX = "client_message_delivery_prepared:";
    private static final String CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX = "client_message_delivery_unknown:";
    private static final String ENTITY_WORKER_ORDER_NEW = ManagerControlWorkerTaskLookup.ENTITY_WORKER_ORDER_NEW;
    private static final String ORDER_STATUS_TO_PAY = "Выставлен счет";
    private static final String ORDER_STATUS_REMINDER = "Напоминание";
    private final ManagerControlTransactionRunner managerControlTransactionRunner;
    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;
    private final ManagerDailyControlRepository dailyControlRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ClientChatMessageSender clientChatMessageSender;
    private final BadReviewPaymentInstructionOrchestrator paymentInstructionOrchestrator;
    private final ManagerControlAccessPolicy accessPolicy;
    private final ManagerControlCardLifecycle cardLifecycle;
    private final ManagerControlClientMessageText clientMessageText;
    private final ManagerControlConcretePresenter concretePresenter;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManagerControlConcreteItemResponse sendClientMessage(Long concreteItemId, Principal principal, Authentication authentication) {
        boolean stalePreparationReconciled = managerControlTransactionRunner.required(
                () -> reconcileStaleClientMessagePreparation(concreteItemId, principal, authentication)
        );
        if (stalePreparationReconciled) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущая отправка не завершилась. Карточка переведена на ручную сверку; проверьте чат клиента"
            );
        }
        PreparedClientMessage prepared = managerControlTransactionRunner.required(
                () -> prepareClientMessage(concreteItemId, principal, authentication)
        );
        long startedAt = System.currentTimeMillis();
        ClientMessageSendResult result;
        try {
            result = clientChatMessageSender.sendWithOperationId(
                    prepared.company() == null ? null : prepared.company().toMessageCompany(),
                    prepared.managerClientId(),
                    prepared.groupId(),
                    prepared.message(),
                    telegramCopyButton(prepared.paymentInstruction()),
                    prepared.deliveryToken()
            );
        } catch (Exception e) {
            finishClientMessageFailure(prepared, readableException(e), true, authentication);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Исход отправки не подтвержден; проверьте чат клиента", e);
        }
        if (result == null || !result.sent()) {
            boolean unknown = !ClientChatMessageSender.isKnownUnsent(result);
            finishClientMessageFailure(prepared, clientMessageError(result), unknown, authentication);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    (unknown ? "Исход отправки не подтвержден; проверьте чат клиента: "
                            : "Сообщение клиенту не отправлено: ") + clientMessageError(result)
            );
        }
        try {
            return managerControlTransactionRunner.required(
                    () -> finishClientMessageSuccess(prepared, result, startedAt, principal, authentication)
            );
        } catch (RuntimeException finalizeFailure) {
            finishClientMessageFailure(
                    prepared,
                    "сообщение доставлено, но заказ изменился во время отправки; нужна ручная сверка: "
                            + readableException(finalizeFailure),
                    true,
                    authentication
            );
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сообщение доставлено, но заказ изменился. Проверьте чат и карточку вручную",
                    finalizeFailure
            );
        }
    }

    private boolean reconcileStaleClientMessagePreparation(
            Long concreteItemId,
            Principal principal,
            Authentication authentication
    ) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findByIdForUpdate(concreteItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        accessPolicy.requireControlAccess(concreteItem.getControl(), principal, authentication);
        if (!safe(concreteItem.getComment()).startsWith(CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX)) {
            return false;
        }

        LocalDateTime preparedAt = concreteItem.getLastManualTouchAt();
        LocalDateTime now = LocalDateTime.now();
        if (preparedAt != null && preparedAt.isAfter(now.minus(CLIENT_MESSAGE_PREPARED_STALE_AFTER))) {
            return false;
        }

        concreteItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        concreteItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setComment(limit(
                CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX
                        + safe(concreteItem.getComment()).substring(CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX.length())
                        + "; подготовка отправки прервалась; исход неизвестен, проверьте чат клиента вручную",
                1000
        ));
        dailyControlConcreteItemRepository.save(concreteItem);
        return true;
    }

    private PreparedClientMessage prepareClientMessage(
            Long concreteItemId,
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
        if (concreteItem.getStatus() == ManagerDailyControlItemStatus.RESOLVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Карточка уже закрыта");
        }
        if (safe(concreteItem.getComment()).startsWith("client_message_delivery_")) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Предыдущая отправка еще не завершена. Проверьте чат клиента перед повтором"
            );
        }
        String entityType = safe(concreteItem.getEntityType());
        if (!"ORDER".equals(entityType) && !ENTITY_WORKER_ORDER_NEW.equals(entityType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Автоотправка клиенту доступна только для заказов");
        }
        Order order = orderRepository.findByIdForCounterUpdate(concreteItem.getEntityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        accessPolicy.requireCurrentOrderAccess(order.getId(), authentication);
        BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction paymentInstruction =
                clientMessageText.isPaymentControlOrder(order)
                        ? paymentInstructionOrchestrator.prepareAuthorized(order.getId(), authentication)
                        : null;
        String message = paymentInstruction != null
                ? paymentInstruction.copyText()
                : clientMessageText.clientControlMessage(concreteItem, order);
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для карточки не удалось собрать текст клиенту");
        }
        String deliveryToken = UUID.randomUUID().toString();
        PreparedClientMessage prepared = new PreparedClientMessage(
                concreteItem.getId(),
                order.getId(),
                order.getManager() == null ? null : order.getManager().getId(),
                clientMessageText.orderStatusTitle(order),
                ManagerControlMessageCompany.capture(order.getCompany()),
                order.getManager() == null ? null : order.getManager().getClientId(),
                order.getCompany() == null ? null : order.getCompany().getGroupId(),
                message,
                paymentInstruction,
                deliveryToken,
                concreteItem.getStatus(),
                concreteItem.getActionType(),
                concreteItem.getComment(),
                concreteItem.getLastManualTouchAt(),
                concreteItem.getFollowUpAt(),
                concreteItem.getResolvedAt(),
                concreteItem.isAutomaticResolution()
        );
        concreteItem.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
        concreteItem.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
        concreteItem.setLastManualTouchAt(LocalDateTime.now());
        concreteItem.setComment(limit(CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX + deliveryToken, 1000));
        dailyControlConcreteItemRepository.save(concreteItem);
        return prepared;
    }

    private ManagerControlConcreteItemResponse finishClientMessageSuccess(
            PreparedClientMessage prepared,
            ClientMessageSendResult result,
            long startedAt,
            Principal principal,
            Authentication authentication
    ) {
        ManagerDailyControlConcreteItem concreteItem = lockedPreparedClientMessage(prepared);
        ManagerDailyControl control = concreteItem.getControl();
        accessPolicy.requireControlAccess(control, principal, authentication);
        Order order = orderRepository.findByIdForCounterUpdate(prepared.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        accessPolicy.requireCurrentOrderAccess(order.getId(), authentication);
        Long currentManagerId = order.getManager() == null ? null : order.getManager().getId();
        if (!Objects.equals(prepared.orderManagerId(), currentManagerId)
                || !Objects.equals(prepared.orderStatus(), clientMessageText.orderStatusTitle(order))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Менеджер или статус заказа изменился во время отправки"
            );
        }
        LocalDateTime now = LocalDateTime.now();
        cardLifecycle.recordConcreteEpisode(concreteItem, ManagerDailyControlItemStatus.RESOLVED, false);
        concreteItem.setStatus(ManagerDailyControlItemStatus.RESOLVED);
        concreteItem.setActionType(ManagerDailyControlActionType.RESOLVED);
        concreteItem.setLastManualTouchAt(now);
        concreteItem.setFollowUpAt(null);
        concreteItem.setResolvedAt(now);
        concreteItem.setAutomaticResolution(false);
        String statusNote = applyOrderStatusAfterClientSend(concreteItem, order);
        concreteItem.setComment(limit("Сообщение клиенту отправлено через " + safe(result.channel()) + statusNote, 1000));
        ManagerDailyControlConcreteItem savedConcreteItem = dailyControlConcreteItemRepository.save(concreteItem);

        cardLifecycle.updateParentItemFromConcreteItems(savedConcreteItem.getParentItem());

        if (control.getStartedAt() == null) {
            control.setStartedAt(now);
        }
        control.setLastActivityAt(now);
        control.setStatus(cardLifecycle.recalculateControlStatus(control));
        dailyControlRepository.save(control);

        cardLifecycle.saveEvent(
                control,
                savedConcreteItem.getParentItem(),
                accessPolicy.actorUserId(principal),
                ManagerDailyControlEventType.ITEM_ACTION,
                ManagerDailyControlActionType.RESOLVED,
                "Клиенту отправлено сообщение по карточке: " + concreteItem.getTitle()
                        + " через " + safe(result.channel())
                        + " за " + (System.currentTimeMillis() - startedAt) + " мс"
                        + statusNote
        );

        return concretePresenter.concreteItemResponse(savedConcreteItem, prepared.message());
    }

    private void finishClientMessageFailure(
            PreparedClientMessage prepared,
            String error,
            boolean deliveryOutcomeUnknown,
            Authentication authentication
    ) {
        try {
            managerControlTransactionRunner.required(() -> {
                ManagerDailyControlConcreteItem item = lockedPreparedClientMessage(prepared);
                if (deliveryOutcomeUnknown) {
                    item.setStatus(ManagerDailyControlItemStatus.ACTION_TAKEN);
                    item.setActionType(ManagerDailyControlActionType.ACTION_TAKEN);
                    item.setComment(limit(
                            CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX + prepared.deliveryToken() + "; исход отправки не подтвержден; "
                                    + "проверьте чат клиента перед повтором: " + safe(error),
                            1000
                    ));
                } else {
                    // Keep the card lock until the exact pristine payment source is released.
                    // A stale finalizer must never release another attempt's source or reopen its card.
                    if (prepared.paymentInstruction() != null) {
                        paymentInstructionOrchestrator.releaseKnownUnsent(prepared.paymentInstruction(), authentication);
                    }
                    item.setStatus(prepared.previousStatus());
                    item.setActionType(prepared.previousActionType());
                    item.setComment(prepared.previousComment());
                    item.setLastManualTouchAt(prepared.previousLastManualTouchAt());
                    item.setFollowUpAt(prepared.previousFollowUpAt());
                    item.setResolvedAt(prepared.previousResolvedAt());
                    item.setAutomaticResolution(prepared.previousAutomaticResolution());
                }
                dailyControlConcreteItemRepository.save(item);
                return null;
            });
        } catch (RuntimeException finalizeFailure) {
            log.error("Не удалось зафиксировать результат отправки карточки {}", prepared.concreteItemId(), finalizeFailure);
        }
    }

    private ManagerDailyControlConcreteItem lockedPreparedClientMessage(PreparedClientMessage prepared) {
        ManagerDailyControlConcreteItem item = dailyControlConcreteItemRepository.findByIdForUpdate(prepared.concreteItemId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        String expected = CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX + prepared.deliveryToken();
        if (!expected.equals(safe(item.getComment()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состояние отправки карточки изменилось. Проверьте чат клиента"
            );
        }
        return item;
    }

    private record PreparedClientMessage(
            Long concreteItemId,
            Long orderId,
            Long orderManagerId,
            String orderStatus,
            ManagerControlMessageCompany company,
            String managerClientId,
            String groupId,
            String message,
            BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction paymentInstruction,
            String deliveryToken,
            ManagerDailyControlItemStatus previousStatus,
            ManagerDailyControlActionType previousActionType,
            String previousComment,
            LocalDateTime previousLastManualTouchAt,
            LocalDateTime previousFollowUpAt,
            LocalDateTime previousResolvedAt,
            boolean previousAutomaticResolution
    ) {
    }

    private TelegramTransferCopyButton telegramCopyButton(
            BadReviewPaymentInstructionOrchestrator.PreparedPaymentInstruction paymentInstruction
    ) {
        return paymentInstruction == null
                ? null
                : TelegramTransferCopyButton.fromFrozenTransferNumber(
                        paymentInstruction.telegramCopyTransferNumber()
                ).orElse(null);
    }

    private String applyOrderStatusAfterClientSend(ManagerDailyControlConcreteItem concreteItem, Order order) {
        String currentStatus = clientMessageText.orderStatusTitle(order);
        String targetStatus = switch (currentStatus) {
            case "Опубликовано" -> ORDER_STATUS_TO_PAY;
            case ORDER_STATUS_TO_PAY -> ORDER_STATUS_REMINDER;
            default -> "";
        };
        if (targetStatus.isBlank() || targetStatus.equals(currentStatus)) {
            return "";
        }
        try {
            boolean changed = orderService.changeStatusForOrder(order.getId(), targetStatus);
            if (changed) {
                concreteItem.setStatusLabel(targetStatus);
                return ". Статус заказа переведен в " + targetStatus;
            }
            return ". Сообщение отправлено, но статус заказа не изменился";
        } catch (Exception e) {
            return ". Сообщение отправлено, но статус заказа не изменился: " + readableException(e);
        }
    }

    private String clientMessageError(ClientMessageSendResult result) {
        if (result == null) {
            return "нет ответа от сервиса отправки";
        }
        String message = safe(result.errorMessage());
        if (!message.isBlank()) {
            return message;
        }
        String code = safe(result.errorCode());
        return code.isBlank() ? "сервис отправки не подтвердил доставку" : code;
    }

    private String readableException(Exception e) {
        if (e == null) {
            return "неизвестная ошибка";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
