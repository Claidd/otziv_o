package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.client_messages.service.ClientChatMessageSender;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceDeliveryService {

    private final CommonInvoiceDetailsAssembler invoiceDetailsAssembler;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoicePresenter invoicePresenter;

    private final CommonInvoiceSettlementService settlementService;

    static final Set<CommonInvoiceStatus> REMINDER_STATUSES = Set.of(CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID);

    static final Set<CommonInvoiceStatus> UNSENT_ACTION_STATUSES = Set.of(CommonInvoiceStatus.READY, CommonInvoiceStatus.PARTIALLY_PAID);

    static final Set<CommonInvoiceStatus> SEND_INVOICE_STATUSES = Set.of(CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID);

    static final String PAYMENT_ROUTE_CHANGED_MESSAGE_PENDING = "payment_route_changed_message_pending";

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final ManagerRepository managerRepository;

    @Autowired
    @Lazy
    private OrderStatusTransitionService orderStatusTransitionService;

    private final ManagerPermissionService managerPermissionService;

    private final UserService userService;

    private final ClientChatMessageSender messageSender;

    private final PaperInvoiceManagerNotificationService paperInvoiceManagerNotificationService;

    private final AppSettingService appSettingService;

    public CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual) {
        return sendInvoice(invoiceId, manual, false);
    }

    CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual, boolean paymentRouteChanged) {
        sendInvoiceMessage(invoiceId, manual, paymentRouteChanged, true);
        return writeTransaction(() -> invoice(invoiceId));
    }

    void sendInvoiceMessage(Long invoiceId, boolean manual, boolean paymentRouteChanged, boolean checkVisibility) {
        PreparedCommonInvoiceMessage preparedMessage = writeTransaction(() -> preparePaymentMessage(invoiceId, false, manual, false, null, checkVisibility));
        PreparedCommonInvoiceMessage prepared = paymentRouteChanged && preparedMessage != null && !preparedMessage.paymentRouteChanged() ? preparedMessage.asPaymentRouteChanged(paymentRouteChangedMessage(preparedMessage.message())) : preparedMessage;
        if (prepared != null) {
            ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
            writeTransaction(() -> {
                finishPaymentMessageSend(prepared, result);
                return null;
            });
        }
    }

    void resetToReadyOnlyBeforeFirstSend(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.COLLECTING || invoice.getStatus() == CommonInvoiceStatus.READY) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoice.setSentAt(null);
        }
    }

    boolean shouldManualMarkInvoiceToPay(CommonInvoice invoice) {
        return invoice != null && (invoice.getStatus() == CommonInvoiceStatus.COLLECTING || invoice.getStatus() == CommonInvoiceStatus.READY);
    }

    public int sendDueReminders(int limit) {
        if (!automaticPaymentRemindersEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        List<CommonInvoice> invoices = invoiceRepository.findReminderCandidates(REMINDER_STATUSES, now, PageRequest.of(0, Math.max(1, limit)));
        int sent = 0;
        for (CommonInvoice candidate : invoices) {
            PreparedCommonInvoiceMessage prepared = writeTransaction(() -> preparePaymentMessage(candidate.getId(), true, false, true, now, false));
            if (prepared != null) {
                ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
                boolean delivered = writeTransaction(() -> finishPaymentMessageSend(prepared, result));
                if (delivered) {
                    sent++;
                }
            }
        }
        return sent;
    }

    public int sendUnsentActionInvoices(int limit) {
        LocalDateTime readyBefore = LocalDateTime.now().minusMinutes(5);
        Pageable page = PageRequest.of(0, Math.max(1, limit));
        List<CommonInvoice> invoices = immediateClientMessagesEnabled() ? invoiceRepository.findUnsentActionCandidates(UNSENT_ACTION_STATUSES, readyBefore, page) : invoiceRepository.findPendingPaymentRouteChangeCandidates(UNSENT_ACTION_STATUSES, readyBefore, page);
        int sent = 0;
        for (CommonInvoice candidate : invoices) {
            try {
                PreparedCommonInvoiceMessage prepared = writeTransaction(() -> preparePaymentMessage(candidate.getId(), false, false, false, null, false));
                if (prepared == null) {
                    continue;
                }
                ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
                boolean delivered = writeTransaction(() -> finishPaymentMessageSend(prepared, result));
                if (delivered) {
                    sent++;
                }
            } catch (RuntimeException exception) {
                log.warn("Common billing unsent invoice skipped; other invoices will continue: invoiceId={}, failure={}", candidate == null ? null : candidate.getId(), readableException(exception));
            }
        }
        return sent;
    }

    @Transactional
    public CommonInvoiceDetailsResponse invoice(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        return invoiceDetails(invoice, items);
    }

    boolean isOwnerPaperInvoice(CommonInvoice invoice) {
        return CommonInvoiceRouteState.isOwnerPaperInvoice(invoice);
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    /**
     * Establishes the same lock order used by standalone payment mutations:
     * Order aggregates, their PaymentLink rows, and only then the common invoice.
     * Membership is checked again while all locks are held so a pre-lock snapshot
     * can never authorize or reconcile a different invoice composition.
     */
    LockedInvoicePaymentPrelude lockedInvoiceAfterStandalonePaymentPrelude(Long invoiceId) {
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregates(invoiceId);
        Map<Long, List<PaymentLink>> paymentLinksByOrder = lockPaymentLinksForOrders(lockedOrderIds);
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureInvoiceMembershipUnchanged(invoiceId, lockedOrderIds);
        return new LockedInvoicePaymentPrelude(invoice, paymentLinksByOrder);
    }

    Optional<CommonInvoice> lockedInvoiceAfterOrderPrelude(Long invoiceId) {
        return settlementService.lockedInvoiceAfterOrderPrelude(invoiceId);
    }

    Set<Long> lockInvoiceOrderAggregates(Long invoiceId) {
        return settlementService.lockInvoiceOrderAggregates(invoiceId);
    }

    Map<Long, List<PaymentLink>> lockPaymentLinksForOrders(Collection<Long> orderIds) {
        return invoiceInitialization.lockPaymentLinksForOrders(orderIds);
    }

    Map<Long, List<PaymentLink>> paymentLinksRequiringCommonInvoiceRouteCheck(Map<Long, List<PaymentLink>> paymentLinksByOrder, Collection<CommonInvoiceOrder> items, Set<PaymentLink> appliedStandalonePayments) {
        return settlementService.paymentLinksRequiringCommonInvoiceRouteCheck(paymentLinksByOrder, items, appliedStandalonePayments);
    }

    int closeProvablyUnstartedStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder, Long invoiceId) {
        return invoiceInitialization.closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksByOrder, invoiceId);
    }

    Set<PaymentLink> synchronizeConfirmedStandalonePaymentsOrThrow(CommonInvoice invoice, List<CommonInvoiceOrder> items, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        return settlementService.synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentLinksByOrder);
    }

    void markStandalonePaymentRouteConflict(CommonInvoice invoice, ResponseStatusException conflict) {
        settlementService.markStandalonePaymentRouteConflict(invoice, conflict);
    }

    void ensureInvoiceMembershipUnchanged(Long invoiceId, Set<Long> lockedOrderIds) {
        settlementService.ensureInvoiceMembershipUnchanged(invoiceId, lockedOrderIds);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    PreparedCommonInvoiceMessage preparePaymentMessage(Long invoiceId, boolean reminder, boolean manual, boolean dueOnly, LocalDateTime dueNow, boolean checkVisibility) {
        if (reminder && dueOnly && !automaticPaymentRemindersEnabled()) {
            return null;
        }
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        if (dueOnly && !isStillDueReminderCandidate(invoice, dueNow)) {
            return null;
        }
        if (checkVisibility) {
            ensureCommonInvoiceVisibleForCurrentUser(invoice);
        }
        if (invoiceInitialization.hasUnresolvedLegacyMessage(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Историческая отправка не имеет сохранённого идентификатора. Требуется сверка до новой отправки");
        }
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanSendPaymentMessages(invoice);
        ensureNoOperationInProgress(invoice);
        boolean paymentRouteChangedRetry = isPaymentRouteChangedMessagePending(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        try {
            Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentPrelude.paymentLinksByOrder());
            closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksRequiringCommonInvoiceRouteCheck(paymentPrelude.paymentLinksByOrder(), items, appliedStandalonePayments), invoice.getId());
        } catch (ResponseStatusException conflict) {
            markStandalonePaymentRouteConflict(invoice, conflict);
            return null;
        }
        refreshInvoiceAmounts(invoice, items);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        if (hasActiveRecovery(items)) {
            if (dueOnly) {
                postponeInvoiceForRecovery(invoice);
                return null;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет ждет завершения задач восстановления отзывов");
        }
        if (reminder) {
            ensureCommonInvoiceReadyForReminder(invoice, items);
        } else {
            ensureCommonInvoiceReadyForInvoiceSend(invoice, items);
        }
        if (remainingKopecks(invoice) <= 0) {
            closePaidInvoice(invoice, items);
            return null;
        }
        if (!appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true)) {
            if (paymentRouteChangedRetry && !reminder) {
                invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID : CommonInvoiceStatus.READY);
                invoice.setSentAt(null);
            } else if (reminder && manual) {
                invoice.setStatus(CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice);
            } else if (!reminder && manual && shouldManualMarkInvoiceToPay(invoice)) {
                invoice.setStatus(CommonInvoiceStatus.INVOICED);
                invoice.setSentAt(LocalDateTime.now());
                markInvoiceOrdersToPay(invoice);
            } else if (!reminder) {
                resetToReadyOnlyBeforeFirstSend(invoice);
            }
            invoice.setNextReminderAt(null);
            invoice.setLastError(paymentRouteChangedRetry ? normalize(invoice.getLastError()) : reminder ? "dry_run: напоминание общего счета не отправлено, live-режим выключен" : "dry_run: сообщение общего счета не отправлено, live-режим выключен");
            invoiceRepository.save(invoice);
            return null;
        }
        if (!isOwnerPaperInvoice(invoice)) {
            ensureCommonPaymentRouteSelected(invoice, remainingKopecks(invoice));
        }
        Company chatCompany = chatCompany(invoice);
        Manager messageManager = manager(invoice, items);
        String operationKind = paymentRouteChangedRetry ? "ROUTE_CHANGE" : reminder ? "REMINDER" : "INVOICE";
        if (invoice.getPaymentMessageOperationId() != null && !invoice.isPaymentMessageConfirmed()
                && !operationKind.equals(invoice.getPaymentMessageOperationKind())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Предыдущая отправка общего счета ещё не подтверждена; сначала требуется сверка");
        }
        boolean confirmedReplay = invoice.isPaymentMessageConfirmed() && !reminder && !paymentRouteChangedRetry
                && operationKind.equals(invoice.getPaymentMessageOperationKind());
        if (invoice.getPaymentMessageOperationId() == null || (invoice.isPaymentMessageConfirmed() && !confirmedReplay)) {
            invoice.setPaymentMessageOperationId("invoice-message:" + invoice.getId() + ":" + java.util.UUID.randomUUID());
            invoice.setPaymentMessageOperationKind(operationKind);
            invoice.setPaymentMessageConfirmed(false);
            invoice.setPaymentMessageChannel(null);
        }
        if (confirmedReplay) {
            return new PreparedCommonInvoiceMessage(invoice.getId(), chatCompany,
                    messageManager == null ? null : messageManager.getClientId(),
                    chatCompany == null ? null : chatCompany.getGroupId(), "", null, reminder, manual,
                    paymentRouteChangedRetry, invoice.getPaymentMessageOperationId(), true, invoice.getPaymentMessageChannel());
        }
        invoice.setLastError(paymentRouteChangedRetry ? PAYMENT_ROUTE_CHANGED_MESSAGE_IN_PROGRESS : MESSAGE_SEND_IN_PROGRESS);
        invoiceRepository.save(invoice);
        return new PreparedCommonInvoiceMessage(invoice.getId(), chatCompany, messageManager == null ? null : messageManager.getClientId(), chatCompany == null ? null : chatCompany.getGroupId(), paymentRouteChangedRetry ? paymentRouteChangedMessage(invoiceMessage(invoice, items, reminder)) : invoiceMessage(invoice, items, reminder), telegramCopyTransferNumber(invoice), reminder, manual, paymentRouteChangedRetry, invoice.getPaymentMessageOperationId(), false, null);
    }

    ClientMessageSendResult sendPreparedPaymentMessage(PreparedCommonInvoiceMessage prepared) {
        if (prepared.alreadyConfirmed()) return ClientMessageSendResult.sent(prepared.confirmedChannel());
        try {
            TelegramTransferCopyButton copyButton = TelegramTransferCopyButton.fromFrozenTransferNumber(prepared.telegramCopyTransferNumber()).orElse(null);
            return messageSender.sendWithOperationId(prepared.chatCompany(), prepared.managerClientId(),
                    prepared.groupId(), prepared.message(), copyButton, prepared.operationId());
        } catch (RuntimeException e) {
            return ClientMessageSendResult.failed("send_exception", readableException(e));
        }
    }

    boolean finishPaymentMessageSend(PreparedCommonInvoiceMessage prepared, ClientMessageSendResult result) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!java.util.Objects.equals(invoice.getPaymentMessageOperationId(), prepared.operationId())) return false;
        if (invoice.isPaymentMessageConfirmed()) return prepared.alreadyConfirmed() || result.sent();
        String inProgressState = normalize(invoice.getLastError());
        boolean expectedInProgressState = MESSAGE_SEND_IN_PROGRESS.equals(inProgressState) || (prepared.paymentRouteChanged() && PAYMENT_ROUTE_CHANGED_MESSAGE_IN_PROGRESS.equals(inProgressState));
        if (!expectedInProgressState) {
            return false;
        }
        if (result.sent()) {
            invoice.setPaymentMessageConfirmed(true);
            invoice.setPaymentMessageChannel(result.channel());
            if (prepared.reminder()) {
                invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID : CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice);
            } else {
                invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID : CommonInvoiceStatus.INVOICED);
                invoice.setSentAt(LocalDateTime.now());
                markInvoiceOrdersToPay(invoice);
            }
            invoice.setNextReminderAt(isOwnerPaperInvoice(invoice) && invoice.getPaperInvoiceIssuedAt() == null ? null : nextAutomaticPaymentReminderAt(LocalDateTime.now()));
            invoice.setLastError(null);
            invoiceRepository.save(invoice);
            if (!prepared.reminder() && isOwnerPaperInvoice(invoice) && invoice.getPaperInvoiceIssuedAt() == null) {
                paperInvoiceManagerNotificationService.notifyAfterCommit(invoice.getId());
            }
            return true;
        }
        if (prepared.reminder()) {
            if (prepared.manual()) {
                invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID : CommonInvoiceStatus.REMINDER);
                invoice.setLastReminderAt(LocalDateTime.now());
                markInvoiceOrdersReminder(invoice);
            }
            invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        } else if (prepared.paymentRouteChanged()) {
            invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID : CommonInvoiceStatus.READY);
            invoice.setSentAt(null);
            invoice.setNextReminderAt(null);
        } else if (prepared.manual()) {
            invoice.setStatus(CommonInvoiceStatus.INVOICED);
            invoice.setSentAt(LocalDateTime.now());
            markInvoiceOrdersToPay(invoice);
        } else {
            resetToReadyOnlyBeforeFirstSend(invoice);
        }
        String sendFailure = result.errorCode() + ": [operationId=" + prepared.operationId() + "] " + result.errorMessage();
        invoice.setLastError(limit(prepared.paymentRouteChanged() ? PAYMENT_ROUTE_CHANGED_MESSAGE_RETRY + sendFailure : sendFailure, 512));
        if (!prepared.reminder()) {
            if (!prepared.manual()) {
                log.warn("Common invoice {} was ready but not sent: {}", prepared.invoiceId(), invoice.getLastError());
            }
        }
        invoiceRepository.save(invoice);
        return false;
    }

    boolean isStillDueReminderCandidate(CommonInvoice invoice, LocalDateTime now) {
        return invoice != null && REMINDER_STATUSES.contains(invoice.getStatus()) && invoice.getNextReminderAt() != null && !invoice.getNextReminderAt().isAfter(now);
    }

    boolean isPaymentRouteChangedMessagePending(CommonInvoice invoice) {
        String state = normalize(invoice == null ? null : invoice.getLastError());
        return PAYMENT_ROUTE_CHANGED_MESSAGE_PENDING.equals(state) || state.startsWith(PAYMENT_ROUTE_CHANGED_MESSAGE_RETRY);
    }

    void ensureCommonInvoiceNotNeedsAttention(CommonInvoice invoice) {
        ensureNoOperationInProgress(invoice);
        if (invoice != null && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет требует ручной проверки. Обычные действия оплаты и напоминаний временно заблокированы.");
        }
    }

    void ensureNoOperationInProgress(CommonInvoice invoice) {
        invoiceInitialization.ensureNoOperationInProgress(invoice);
    }

    void ensureCommonInvoiceCanSendPaymentMessages(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплаченный общий счет нельзя отправлять клиенту");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет в статусе Не оплачено нельзя отправлять клиенту");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.BAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет в статусе Бан нельзя отправлять клиенту");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отключенный общий счет нельзя отправлять клиенту");
        }
    }

    void ensureCommonInvoiceReadyForInvoiceSend(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null) {
            return;
        }
        if (!SEND_INVOICE_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет еще не готов к отправке");
        }
        ensureAllItemsReady(items, "Общий счет еще собирается: не все заказы готовы к оплате");
    }

    void ensureCommonInvoiceReadyForReminder(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null) {
            return;
        }
        if (!REMINDER_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Напоминание можно отправить только по уже выставленному счету");
        }
        if (isOwnerPaperInvoice(invoice) && invoice.getPaperInvoiceIssuedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала отметьте, что бумажный счёт отправлен клиенту");
        }
        ensureAllItemsReady(items, "Общий счет еще собирается: не все заказы готовы к напоминанию");
    }

    void ensureAllItemsReady(List<CommonInvoiceOrder> items, String message) {
        if (items == null || items.isEmpty() || items.stream().anyMatch(item -> !item.isReady())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.closePaidInvoice(invoice, items);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds) {
        settlementService.closePaidInvoice(invoice, items, alreadyClosedOrderIds);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds, Runnable finalAttribution) {
        settlementService.closePaidInvoice(invoice, items, alreadyClosedOrderIds, finalAttribution);
    }

    /**
     * Performs one provider attempt for the durable common-invoice outbox.
     * Snapshot reads use a short transaction; chat I/O happens only after it
     * has completed. Final durable state is fenced by the outbox claim service.
     */
    public ClientPaymentNotificationAttempt deliverPaymentSuccessNotificationFromOutbox(Long invoiceId) {
        PreparedClientPaymentNotification prepared = writeTransaction(() -> {
            CommonInvoice invoice = invoiceRepository.findByIdWithAccount(invoiceId).orElse(null);
            if (invoice == null) {
                return PreparedClientPaymentNotification.skipped("invoice_missing");
            }
            if (invoice.getPaymentSuccessNotifiedAt() != null) {
                return PreparedClientPaymentNotification.skipped("already_notified");
            }
            if (invoice.getStatus() != CommonInvoiceStatus.PAID || invoice.getPaidKopecks() < invoice.getAmountKopecks()) {
                return PreparedClientPaymentNotification.skipped("invoice_not_paid");
            }
            if (!immediateClientMessagesEnabled()) {
                return PreparedClientPaymentNotification.failed("immediate_messages_disabled: моментальные клиентские сообщения выключены");
            }
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
            Company company = chatCompany(invoice, items);
            Manager manager = manager(invoice, items);
            return PreparedClientPaymentNotification.ready(company, manager == null ? null : manager.getClientId(), company == null ? null : company.getGroupId(), paymentSuccessMessage(invoice, items));
        });
        if (prepared.skipped()) {
            return ClientPaymentNotificationAttempt.skipped(prepared.error());
        }
        if (!normalize(prepared.error()).isBlank()) {
            return ClientPaymentNotificationAttempt.failed(prepared.error());
        }
        try {
            ClientMessageSendResult result = messageSender.sendWithOperationId(prepared.company(), prepared.managerClientId(),
                    prepared.groupId(), prepared.message(), null, "invoice-payment-success:" + invoiceId);
            if (result != null && result.sent()) {
                return ClientPaymentNotificationAttempt.sent(result.channel());
            }
            return ClientPaymentNotificationAttempt.failed(clientMessageError(result));
        } catch (RuntimeException exception) {
            return ClientPaymentNotificationAttempt.failed(readableException(exception));
        }
    }

    String paymentSuccessMessage(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        String payerEmail = normalize(invoice.getPayerEmail());
        boolean manualRequisites = isManualMobileBankCommonRoute(invoice);
        StringBuilder message = new StringBuilder().append(manualRequisites ? "Оплата по реквизитам подтверждена." : "Оплата прошла успешно.");
        if (manualRequisites) {
            message.append("\n\nМенеджер сверил перевод по реквизитам. Заказы приняты в работу.");
        }
        message.append("\n\nОбщий счет: ").append(invoice.getAccount().getName()).append("\nЗаказов: ").append(items == null ? 0 : items.size()).append("\nСумма: ").append(money(amountRubles(invoice.getPaidKopecks()))).append(" руб.");
        if (!manualRequisites) {
            message.append("\nСтраница оплаты: ").append(publicInvoiceUrl(invoice));
        }
        return message.append("\n\n").append(payerEmail.isBlank() ? "Чек будет отправлен на e-mail." : "Чек будет отправлен на e-mail: " + payerEmail + ".").toString();
    }

    String clientMessageError(ClientMessageSendResult result) {
        if (result == null) {
            return "notification_result_empty";
        }
        String code = normalize(result.errorCode());
        String message = normalize(result.errorMessage());
        if (code.isBlank()) {
            return message.isBlank() ? "notification_not_sent" : message;
        }
        return message.isBlank() ? code : code + ": " + message;
    }

    String orderFailureLabel(CommonInvoiceOrder item) {
        return settlementService.orderFailureLabel(item);
    }

    String orderFailureLabel(Order order) {
        return settlementService.orderFailureLabel(order);
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.refreshInvoiceAmounts(invoice, items);
    }

    CommonInvoiceDetailsResponse invoiceDetails(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceDetailsAssembler.invoiceDetails(invoice, items);
    }

    boolean visibleToManager(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> visibleManagerIds) {
        if (visibleManagerIds == null) {
            return true;
        }
        if (visibleManagerIds.isEmpty()) {
            return false;
        }
        Manager accountManager = invoice.getAccount().getManager();
        if (accountManager != null && visibleManagerIds.contains(accountManager.getId())) {
            return true;
        }
        return items != null && !items.isEmpty() && items.stream().map(item -> item.getOrder() == null ? null : item.getOrder().getManager()).allMatch(manager -> manager != null && manager.getId() != null && visibleManagerIds.contains(manager.getId()));
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        Set<Long> visibleManagerIds = visibleManagerIdsForCurrentUser();
        if (visibleManagerIds == null) {
            return;
        }
        List<CommonInvoiceOrder> items = invoice == null || invoice.getId() == null ? List.of() : invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (!visibleToManager(invoice, items, visibleManagerIds)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Общий счет недоступен текущему пользователю");
        }
    }

    Set<Long> visibleManagerIdsForCurrentUser() {
        Authentication authentication = currentAuthentication();
        if (authentication == null) {
            return null;
        }
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            return null;
        }
        if (managerPermissionService.hasRole(authentication, "OWNER")) {
            return userService.findManagersByUserName(authentication.getName()).stream().map(Manager::getId).filter(id -> id != null).collect(Collectors.toSet());
        }
        if (managerPermissionService.hasRole(authentication, "MANAGER")) {
            return userService.findByUserName(authentication.getName()).flatMap(user -> managerRepository.findByUserId(user.getId())).map(Manager::getId).map(Set::of).orElse(Set.of());
        }
        return Set.of();
    }

    Authentication currentAuthentication() {
        return SecurityContextHolder.getContext() == null ? null : SecurityContextHolder.getContext().getAuthentication();
    }

    boolean hasActiveRecovery(List<CommonInvoiceOrder> items) {
        return settlementService.hasActiveRecovery(items);
    }

    boolean hasActiveRecovery(CommonInvoiceOrder item) {
        return settlementService.hasActiveRecovery(item);
    }

    void postponeInvoiceForRecovery(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        invoice.setLastError("review_recovery_active: есть активные задачи восстановления отзывов");
        invoiceRepository.save(invoice);
    }

    String invoiceMessage(CommonInvoice invoice, List<CommonInvoiceOrder> items, boolean reminder) {
        StringBuilder builder = new StringBuilder();
        builder.append(invoice.getAccount().getName()).append("\n\n");
        builder.append(reminder ? "Напоминаем об оплате общего счета." : "Все заказы из общего счета выполнены.");
        builder.append("\n\nЗаказов: ").append(items.size());
        builder.append("\nК оплате: ").append(money(amountRubles(remainingKopecks(invoice)))).append(" руб.");
        if (isOwnerPaperInvoice(invoice)) {
            builder.append("\n\n");
            if (reminder) {
                builder.append("Напоминаем об оплате по выставленному бумажному счёту.");
            } else {
                builder.append("Оплата производится по выставленному счёту. Документ будет отправлен в этот чат.");
            }
            builder.append("\nПосле оплаты отправьте платёжное поручение в этот чат.");
        } else if (isManualMobileBankCommonRoute(invoice)) {
            builder.append("\n\n").append(commonPaymentInstructionText(invoice));
            builder.append("\n\nПосле оплаты отправьте чек в этот чат.");
        } else if (isManagerTextCommonRoute(invoice)) {
            builder.append("\n\n").append(commonPaymentInstructionText(invoice));
            builder.append("\nСтраница общего счета: ").append(publicInvoiceUrl(invoice));
        } else {
            builder.append("\nСсылка на оплату: ").append(publicInvoiceUrl(invoice));
        }
        builder.append("\n\nСостав:");
        items.stream().limit(12).forEach(item -> builder.append("\n- №").append(item.getOrder().getId()).append(" ").append(item.getOrder().getCompany() == null ? "" : item.getOrder().getCompany().getTitle()).append(item.getOrder().getFilial() == null ? "" : " / " + item.getOrder().getFilial().getTitle()).append(" - ").append(money(amountRubles(item.getAmountKopecks()))).append(" руб."));
        if (items.size() > 12) {
            builder.append("\n- еще ").append(items.size() - 12).append(" заказов");
        }
        return builder.toString();
    }

    String paymentRouteChangedMessage(String invoiceMessage) {
        return "⚠️ Способ оплаты изменён. Не оплачивайте по ранее отправленным реквизитам или ссылке. " + "Используйте только новый способ оплаты ниже.\n\n" + normalize(invoiceMessage);
    }

    String commonPaymentInstructionText(CommonInvoice invoice) {
        if (invoice != null && invoice.getContractorAllocationId() != null && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            return commonContractorPaymentInstruction(requiredPreparedCommonContractorRequisites(invoice));
        }
        return normalize(invoice == null ? null : invoice.getPaymentRouteInstructionText());
    }

    ContractorPaymentRequisitesSnapshot requiredPreparedCommonContractorRequisites(CommonInvoice invoice) {
        return invoicePresenter.requiredPreparedCommonContractorRequisites(invoice);
    }

    String commonContractorPaymentInstruction(ContractorPaymentRequisitesSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        String transfer = normalize(snapshot.paymentPhone());
        StringBuilder instruction = new StringBuilder("Оплата по мобильному банку: ").append(transfer).append("\nПолучатель: ").append(normalize(snapshot.recipientName()));
        if (!normalize(snapshot.bankName()).isBlank()) {
            instruction.append("\nБанк: ").append(normalize(snapshot.bankName()));
        }
        if (!normalize(snapshot.paymentComment()).isBlank()) {
            instruction.append("\nКомментарий: ").append(normalize(snapshot.paymentComment()));
        }
        return instruction.toString();
    }

    String telegramCopyTransferNumber(CommonInvoice invoice) {
        if (invoice == null || !isManualMobileBankCommonRoute(invoice)) {
            return null;
        }
        String value;
        if (invoice.getContractorAllocationId() != null && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            value = requiredPreparedCommonContractorRequisites(invoice).paymentPhone();
        } else {
            value = invoice.getPaymentRouteManualPhone();
        }
        return com.hunt.otziv.contractor_payments.service.ContractorPaymentTransferNumber.isValid(value) ? com.hunt.otziv.contractor_payments.service.ContractorPaymentTransferNumber.normalize(value) : null;
    }

    void ensureCommonPaymentRouteSelected(CommonInvoice invoice, long remainingKopecks) {
        invoiceInitialization.ensureCommonPaymentRouteSelected(invoice, remainingKopecks);
    }

    boolean isManagerTextCommonRoute(CommonInvoice invoice) {
        return invoicePresenter.isManagerTextCommonRoute(invoice);
    }

    boolean isManualMobileBankCommonRoute(CommonInvoice invoice) {
        return invoicePresenter.isManualMobileBankCommonRoute(invoice);
    }

    Company chatCompany(CommonInvoice invoice) {
        return invoiceInitialization.chatCompany(invoice);
    }

    Company chatCompany(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceInitialization.chatCompany(invoice, items);
    }

    Manager manager(CommonInvoice invoice) {
        return invoiceInitialization.manager(invoice);
    }

    Manager manager(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceInitialization.manager(invoice, items);
    }

    boolean immediateClientMessagesEnabled() {
        return settlementService.immediateClientMessagesEnabled();
    }

    boolean automaticPaymentRemindersEnabled() {
        return settlementService.automaticPaymentRemindersEnabled();
    }

    LocalDateTime nextAutomaticPaymentReminderAt(LocalDateTime from) {
        return settlementService.nextAutomaticPaymentReminderAt(from);
    }

    void markInvoiceOrdersToPay(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null || isBadReviewSuccessor(invoice)) {
            return;
        }
        markInvoiceOrdersToStatus(invoice.getId(), STATUS_TO_PAY);
    }

    void markInvoiceOrdersReminder(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null || isBadReviewSuccessor(invoice)) {
            return;
        }
        markInvoiceOrdersToStatus(invoice.getId(), STATUS_REMINDER);
    }

    boolean isBadReviewSuccessor(CommonInvoice invoice) {
        return invoice != null && "BAD_REVIEW_SUCCESSOR".equals(invoice.getInvoicePurpose());
    }

    void markInvoiceOrdersToStatus(Long invoiceId, String status) {
        List<String> failures = new ArrayList<>();
        for (CommonInvoiceOrder item : invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId)) {
            if (item == null || item.isPaid()) {
                continue;
            }
            Order order = item.getOrder();
            if (order == null || order.getId() == null || status.equals(statusTitle(order))) {
                continue;
            }
            try {
                orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), status);
            } catch (Exception e) {
                failures.add(orderFailureLabel(item));
                log.warn("Не удалось перевести заказ {} из общего счета {} в {}", order.getId(), invoiceId, status, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не все заказы общего счета удалось перевести в " + status + ": " + String.join(", ", failures));
        }
    }

    BigDecimal amountRubles(long kopecks) {
        return settlementService.amountRubles(kopecks);
    }

    long remainingKopecks(CommonInvoice invoice) {
        return settlementService.remainingKopecks(invoice);
    }

    String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    String statusTitle(Order order) {
        return settlementService.statusTitle(order);
    }

    String publicInvoiceUrl(CommonInvoice invoice) {
        return invoicePresenter.publicInvoiceUrl(invoice);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }

    String readableException(RuntimeException e) {
        return settlementService.readableException(e);
    }

    record PreparedCommonInvoiceMessage(Long invoiceId, Company chatCompany, String managerClientId, String groupId, String message, String telegramCopyTransferNumber, boolean reminder, boolean manual, boolean paymentRouteChanged, String operationId, boolean alreadyConfirmed, String confirmedChannel) {

        private PreparedCommonInvoiceMessage asPaymentRouteChanged(String replacement) {
            return new PreparedCommonInvoiceMessage(invoiceId, chatCompany, managerClientId, groupId, replacement, telegramCopyTransferNumber, reminder, manual, true, operationId, alreadyConfirmed, confirmedChannel);
        }
    }

    record PreparedClientPaymentNotification(boolean skipped, String error, Company company, String managerClientId, String groupId, String message) {

        private static PreparedClientPaymentNotification ready(Company company, String managerClientId, String groupId, String message) {
            return new PreparedClientPaymentNotification(false, "", company, managerClientId, groupId, message);
        }

        private static PreparedClientPaymentNotification skipped(String reason) {
            return new PreparedClientPaymentNotification(true, reason, null, null, null, null);
        }

        private static PreparedClientPaymentNotification failed(String error) {
            return new PreparedClientPaymentNotification(false, error, null, null, null, null);
        }
    }

    public record ClientPaymentNotificationAttempt(boolean sent, boolean skipped, String channel, String error) {

        private static ClientPaymentNotificationAttempt sent(String channel) {
            return new ClientPaymentNotificationAttempt(true, false, channel, "");
        }

        private static ClientPaymentNotificationAttempt skipped(String reason) {
            return new ClientPaymentNotificationAttempt(false, true, "", reason);
        }

        private static ClientPaymentNotificationAttempt failed(String error) {
            String clean = error == null || error.isBlank() ? "notification_delivery_failed" : error.trim();
            return new ClientPaymentNotificationAttempt(false, false, "", clean);
        }
    }
}
