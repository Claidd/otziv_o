package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
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
import java.math.BigDecimal;
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
    private final ClientMessageDelivery delivery;
    private final ManagerClientMessageQueue queue;
    private final DeferredUserAuthority actors;
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
        return managerControlTransactionRunner.required(() -> {
            PreparedClientMessage prepared = prepareClientMessage(concreteItemId, principal, authentication);
            queue.enqueue(new ManagerClientMessageQueue.Command(prepared.deliveryToken(), concreteItemId, "CONTROL",
                    queue.encode(prepared), actors.capture(authentication)));
            return concretePresenter.concreteItemResponse(lockedPreparedClientMessage(prepared), prepared.message())
                    .withDelivery(queue.status(concreteItemId, prepared.deliveryToken()));
        });
    }

    void validateQueued(ManagerClientMessageQueue.Command command, Authentication authentication) {
        PreparedClientMessage prepared = queuedSnapshot(command);
        managerControlTransactionRunner.required(() -> {
            var card = lockedPreparedClientMessage(prepared);
            accessPolicy.requireControlAccess(card.getControl(), authentication, authentication);
            var order = orderRepository.findByIdForCounterUpdate(prepared.orderId()).orElseThrow();
            accessPolicy.requireCurrentOrderAccess(order.getId(), authentication);
            if (!Objects.equals(prepared.orderManagerId(), order.getManager() == null ? null : order.getManager().getId())
                    || !Objects.equals(prepared.orderStatus(), clientMessageText.orderStatusTitle(order))
                    || !sameAmount(prepared.orderSum(), order.getSum()) || prepared.orderAmount() != order.getAmount()
                    || !Objects.equals(prepared.company(), ManagerControlMessageCompany.capture(order.getCompany()))
                    || !Objects.equals(prepared.managerClientId(), order.getManager() == null ? null : order.getManager().getClientId())
                    || !Objects.equals(prepared.groupId(), order.getCompany() == null ? null : order.getCompany().getGroupId()))
                throw new IllegalStateException("Manager command source changed");
            return null;
        });
    }

    ClientMessageSendResult dispatchQueued(ManagerClientMessageQueue.Command command) {
        PreparedClientMessage p = queuedSnapshot(command);
        return delivery.deliverWithOperationId(p.company() == null ? null : p.company().target(), p.managerClientId(),
                p.groupId(), p.message(), telegramCopyButton(p.paymentInstruction()), p.deliveryToken());
    }

    void completeQueued(ManagerClientMessageQueue.Claim claim, ManagerClientMessageQueue.Command command,
                        ClientMessageSendResult result, Authentication authentication, String state, String code, int delay) {
        PreparedClientMessage prepared = queuedSnapshot(command);
        managerControlTransactionRunner.required(() -> {
            // Same ordering as enqueue: card, order/payment source, then queue claim.
            lockedPreparedClientMessage(prepared);
            orderRepository.findByIdForCounterUpdate(prepared.orderId()).orElseThrow();
            if (!queue.owns(claim)) return null;
            if ("finalization_required".equals(code) || "context_changed".equals(code)) {
                var card = lockedPreparedClientMessage(prepared);
                card.setComment(CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX + prepared.deliveryToken());
                dailyControlConcreteItemRepository.save(card);
                queue.finish(claim, state, code, delay);
                return null;
            }
            if ("SENT".equals(state)) finishClientMessageSuccess(prepared, result, System.currentTimeMillis(), authentication, authentication);
            else if ("FAILED".equals(state)) restoreKnownUnsent(prepared, authentication);
            else if ("UNKNOWN".equals(state)) {
                var card = lockedPreparedClientMessage(prepared);
                card.setComment(CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX + prepared.deliveryToken());
                dailyControlConcreteItemRepository.save(card);
            }
            else if ("RETRYABLE".equals(state) && !claim.mayDispatch() && ClientMessageDelivery.isKnownUnsent(result)) {
                var card = lockedPreparedClientMessage(prepared);
                card.setComment(CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX + prepared.deliveryToken());
                dailyControlConcreteItemRepository.save(card);
            }
            queue.finish(claim, state, code, delay);
            return null;
        });
    }

    private PreparedClientMessage queuedSnapshot(ManagerClientMessageQueue.Command command) {
        PreparedClientMessage prepared = queue.decode(command.preparedJson(), PreparedClientMessage.class);
        if (!Objects.equals(command.operationId(), prepared.deliveryToken()) || command.cardId() != prepared.concreteItemId())
            throw new IllegalStateException("Manager command identity mismatch");
        return prepared;
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

        String queuedToken = safe(concreteItem.getComment()).substring(CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX.length());
        if (queue.status(concreteItemId, queuedToken) != null) return false;

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
                order.getSum(),
                order.getAmount(),
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
                || !Objects.equals(prepared.orderStatus(), clientMessageText.orderStatusTitle(order))
                || !sameAmount(prepared.orderSum(), order.getSum()) || prepared.orderAmount() != order.getAmount()) {
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

    private void restoreKnownUnsent(PreparedClientMessage prepared, Authentication authentication) {
        ManagerDailyControlConcreteItem item = lockedPreparedClientMessage(prepared);
        if (prepared.paymentInstruction() != null)
            paymentInstructionOrchestrator.releaseKnownUnsent(prepared.paymentInstruction(), authentication);
        item.setStatus(prepared.previousStatus());
        item.setActionType(prepared.previousActionType());
        item.setComment(prepared.previousComment());
        item.setLastManualTouchAt(prepared.previousLastManualTouchAt());
        item.setFollowUpAt(prepared.previousFollowUpAt());
        item.setResolvedAt(prepared.previousResolvedAt());
        item.setAutomaticResolution(prepared.previousAutomaticResolution());
        dailyControlConcreteItemRepository.save(item);
    }

    private ManagerDailyControlConcreteItem lockedPreparedClientMessage(PreparedClientMessage prepared) {
        ManagerDailyControlConcreteItem item = dailyControlConcreteItemRepository.findByIdForUpdate(prepared.concreteItemId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        String expected = CLIENT_MESSAGE_DELIVERY_PREPARED_PREFIX + prepared.deliveryToken();
        String unknown = CLIENT_MESSAGE_DELIVERY_UNKNOWN_PREFIX + prepared.deliveryToken();
        if (!expected.equals(safe(item.getComment())) && !unknown.equals(safe(item.getComment()))
                && !safe(item.getComment()).startsWith(unknown + ";")) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Состояние отправки карточки изменилось. Проверьте чат клиента"
            );
        }
        return item;
    }

    record PreparedClientMessage(
            Long concreteItemId,
            Long orderId,
            Long orderManagerId,
            String orderStatus,
            BigDecimal orderSum,
            int orderAmount,
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

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
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
