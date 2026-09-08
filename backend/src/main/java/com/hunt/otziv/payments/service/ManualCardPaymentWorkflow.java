package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentContextResponse;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.PaymentLinkSettlementService.PREPAID_WAITING_ORDER_COMPLETION;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.CancelReservation;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.BankStateObservation;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.ProviderStateObservation;
import static com.hunt.otziv.payments.service.ManualCardRoutePolicy.MANAGER_REPORTED_CARD_PAYMENT_AUDIT_PREFIX;
import static com.hunt.otziv.payments.service.ManualCardRoutePolicy.MANAGER_REPORTED_CARD_PAYMENT_EVIDENCE_PREFIX;
import static com.hunt.otziv.payments.service.ManualCardRoutePolicy.MANUAL_CARD_PAYMENT_AUDIT_PREFIX;
import static com.hunt.otziv.payments.service.ManualCardRoutePolicy.MANUAL_CARD_PAYMENT_COMPLETED_PREFIX;
import static com.hunt.otziv.payments.service.ManualCardRoutePolicy.MANUAL_CARD_PAYMENT_EVIDENCE_PREFIX;
import static com.hunt.otziv.payments.service.ManualCardRoutePolicy.MANUAL_CARD_PAYMENT_PENDING_PREFIX;

/**
 * Settles a reported card transfer: freeze recipient intent, verify/cancel any
 * bank session outside the database transaction, then atomically record the
 * manual payment and its accounting evidence. Unknown bank outcomes stay blocked.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ManualCardPaymentWorkflow {

    private final PaymentLinkSettlementService settlementService;

    private final PaymentLinkLifecycleService lifecycleService;

    private final ManualPaymentConfirmationWorkflow manualConfirmationWorkflow;

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final ManualCardRoutePolicy manualCardRoutePolicy;

    private final BankObservationApplicationService bankObservationApplication;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final TbankPaymentProperties properties;

    private final TbankClient tbankClient;

    private final ManualPaymentRecipientTelegramNotificationService manualPaymentRecipientTelegramNotificationService;

    private final ManualPaymentTaskService manualPaymentTaskService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    private final OrderPaymentIntegrityService orderPaymentIntegrityService;

    private final ManagerAccessService managerAccessService;

    private final ContractorActualPaymentAttributionService actualPaymentAttributionService;

    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private void ensureOrderNotCoveredByActiveCommonInvoice(Long orderId) {
        preparationWorkflow.ensureOrderNotCoveredByActiveCommonInvoice(orderId);
    }

    private boolean hasBankInitReservation(PaymentLink link) {
        return lifecycleService.hasBankInitReservation(link);
    }

    private BankStateObservation observeTbankStateForManualCardPayment(PaymentLink link) {
        return bankObservations.observeTbankStateForManualCardPayment(link);
    }

    private void applyObservedTbankStateIfCurrent(PaymentLink link, BankStateObservation observation, Long lockedOrderId) {
        bankObservationApplication.applyObservedTbankStateIfCurrent(link, observation, lockedOrderId);
    }

    private boolean sameObservedLink(PaymentLink link, ProviderStateObservation observation) {
        return bankObservationApplication.sameObservedLink(link, observation);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManualCardPaymentContextResponse manualCardPaymentContextForOrder(Long orderId, Authentication authentication) {
        OrderManualCardRoute route = selectManualCardPaymentRouteForOrder(orderId, authentication);
        return transactionExecutor.required(() -> {
            Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
            managerAccessService.requireOrderAccess(orderId, authentication);
            ensureOrderNotCoveredByActiveCommonInvoice(orderId);
            PaymentLink link = paymentLinkRepository.findByIdForUpdate(route.linkId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
            if (!hasOrderBinding(link, orderId) || link.getAmountKopecks() != route.amountKopecks()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Платёжный источник заказа изменился");
            }
            return manualCardPaymentContextForRoute(order, link);
        });
    }

    /**
     * Safely settles an order that was paid by a direct transfer while its
     * T-Bank payment page was still open. The provider state is read first. A
     * NEW payment session is canceled through T-Bank and the order is credited
     * only after an explicit CANCELED response. Any paid, authorized,
     * inconsistent or unknown provider state fails closed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmPaidByManualCardTransferForOrder(Long orderId, boolean recipientStatementChecked, boolean paymentReceived, Long receivedAmountKopecks, String note, String receiptUrl, String actor, Authentication authentication) {
        OrderManualCardRoute route = selectManualCardPaymentRouteForOrder(orderId, authentication);
        return confirmPaidByManualCardTransfer(route.linkId(), recipientStatementChecked, paymentReceived, receivedAmountKopecks, note, receiptUrl, null, null, null, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse reportPaidByManualCardTransferForOrder(Long orderId, String reason, String actor, Authentication authentication) {
        return reportPaidByManualCardTransferForOrder(orderId, reason, null, null, null, null, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse reportPaidByManualCardTransferForOrder(Long orderId, String reason, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId, String recipientKey, String actor, Authentication authentication) {
        String cleanReason = normalize(reason);
        if (cleanReason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите причину ручной оплаты");
        }
        if (cleanReason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Причина не должна превышать 500 символов");
        }
        String cleanReceiptUrl = validatedReceiptUrl(receiptUrl);
        if (cleanReceiptUrl.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка на чек не должна превышать 1024 символа");
        }
        OrderManualCardRoute route = selectManualCardPaymentRouteForOrder(orderId, authentication);
        return confirmPaidByManualCardTransferInternal(route.linkId(), route.amountKopecks(), "Причина менеджера: " + cleanReason, cleanReceiptUrl, actor, authentication, new ManualCardPaymentContext(ManualCardPaymentMode.MANAGER_REPORTED, cleanReason), recipientType, recipientProfileId, recipientKey);
    }

    private ManualCardPaymentContextResponse manualCardPaymentContextForRoute(Order order, PaymentLink link) {
        return manualCardRoutePolicy.manualCardPaymentContextForRoute(order, link);
    }

    private boolean isHistoricalPreCutoverManualCardSettlement(PaymentLink link, String recipientKey) {
        return manualCardRoutePolicy.isHistoricalPreCutoverManualCardSettlement(link, recipientKey);
    }

    private void requireHistoricalPreCutoverManualCardSelectionIfNeeded(PaymentLink link, String recipientKey) {
        manualCardRoutePolicy.requireHistoricalPreCutoverManualCardSelectionIfNeeded(link, recipientKey);
    }

    OrderManualCardRoute selectManualCardPaymentRouteForOrder(Long orderId, Authentication authentication) {
        reconcileLegacyTerminalBankRoutesForManualCardPayment(orderId, authentication);
        return transactionExecutor.required(() -> {
            if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
            }
            managerAccessService.requireOrderAccess(orderId, authentication);
            List<PaymentLink> orderLinks = paymentLinkRepository.findByOrderIdForUpdate(orderId);
            PaymentLink selected = selectManualCardPaymentRoute(orderLinks);
            if (!isCompletedManualCardPayment(selected)) {
                ensureOrderNotCoveredByActiveCommonInvoice(orderId);
                boolean competingPayment = orderLinks.stream().filter(candidate -> !sameLinkId(selected, candidate)).anyMatch(this::isCompetingManualCardPaymentRoute);
                if (competingPayment) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа найден другой активный или подтвержденный способ оплаты. " + "Оплата переводом не зачислена; нужна ручная сверка.");
                }
            }
            return new OrderManualCardRoute(selected.getId(), selected.getAmountKopecks());
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmPaidByManualCardTransfer(Long linkId, boolean recipientStatementChecked, boolean paymentReceived, Long receivedAmountKopecks, String note, String receiptUrl, String actor, Authentication authentication) {
        return confirmPaidByManualCardTransfer(linkId, recipientStatementChecked, paymentReceived, receivedAmountKopecks, note, receiptUrl, null, null, null, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmPaidByManualCardTransfer(Long linkId, boolean recipientStatementChecked, boolean paymentReceived, Long receivedAmountKopecks, String note, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId, String recipientKey, String actor, Authentication authentication) {
        String cleanNote = normalize(note);
        String cleanReceiptUrl = validatedReceiptUrl(receiptUrl);
        String cleanActor = normalize(actor);
        if (cleanNote.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка не должна превышать 500 символов");
        }
        if (cleanReceiptUrl.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка на чек не должна превышать 1024 символа");
        }
        if (!recipientStatementChecked || !paymentReceived || receivedAmountKopecks == null || receivedAmountKopecks <= 0 || cleanNote.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите проверку выписки получателя, поступление перевода, точную сумму и укажите обязательную заметку");
        }
        return confirmPaidByManualCardTransferInternal(linkId, receivedAmountKopecks, cleanNote, cleanReceiptUrl, cleanActor, authentication, new ManualCardPaymentContext(ManualCardPaymentMode.OWNER_VERIFIED, cleanNote), recipientType, recipientProfileId, recipientKey);
    }

    AdminPaymentLinkResponse confirmPaidByManualCardTransferInternal(Long linkId, Long receivedAmountKopecks, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey) {
        return confirmPaidByManualCardTransferInternal(linkId, receivedAmountKopecks, note, receiptUrl, actor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey, false);
    }

    AdminPaymentLinkResponse confirmPaidByManualCardTransferInternal(Long linkId, Long receivedAmountKopecks, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey, boolean signedOwnerApproval) {
        if (!signedOwnerApproval) {
            contractorPaymentTargetAccessPolicy.requireCanManagePaymentLink(linkId);
        }
        String cleanNote = normalize(note);
        String cleanReceiptUrl = validatedReceiptUrl(receiptUrl);
        String cleanActor = normalize(actor);
        PaymentLink initialSnapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long orderId = initialSnapshot.getOrder() == null ? null : initialSnapshot.getOrder().getId();
        if (orderId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        if (isActiveOrAmbiguousTochkaRouteForManualCardPayment(initialSnapshot)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Активный или неоднозначный платеж Точки нельзя автоматически закрыть для ручного перевода. " + "Сначала завершите сверку платежа в Точке.");
        }
        reconcileLegacyTerminalBankRoutesForManualCardPayment(orderId, authentication);
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(snapshot, orderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ платежной ссылки изменился во время сверки");
        }
        boolean actualRecipientAccountingRequired = snapshot.getManualActualRecipientFrozenAt() != null || snapshot.getManualSource() == ManualPaymentSource.MANUAL_TASK || transactionExecutor.required(actualPaymentAttributionService::actualRecipientAccountingEnabled);
        if (!isCompletedManualCardPayment(snapshot) && actualRecipientAccountingRequired && requestedRecipientType == null && normalize(requestedRecipientKey).isBlank()) {
            throw ManualPaymentTaskRouteErrors.actualRecipientRequired();
        }
        transactionExecutor.required(() -> {
            if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
            }
            managerAccessService.requireOrderAccess(orderId, authentication);
            return null;
        });
        if (receivedAmountKopecks != snapshot.getAmountKopecks()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма перевода не равна полной сумме заказа. Оплата не зачислена; проверьте выписку и заказ.");
        }
        if (isDirectManualAttributionRoute(snapshot) || isRecoverableExpiredManualRoute(snapshot)) {
            return transactionExecutor.required(() -> applyDirectManualPayment(linkId, orderId, receivedAmountKopecks, cleanNote, cleanReceiptUrl, cleanActor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey));
        }
        if (isCompletedManualCardPayment(snapshot)) {
            return transactionExecutor.required(() -> {
                PaymentLink locked = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
                requireCompletedManualCardPaymentReplay(locked, receivedAmountKopecks, requestedRecipientKey, requestedRecipientType, requestedRecipientProfileId, context.reason(), cleanReceiptUrl);
                return toAdminResponse(locked);
            });
        }
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        AdminPaymentLinkResponse completedDuringPreflight = preflightManualCardPaymentRecipientIntent(linkId, orderId, receivedAmountKopecks, context.reason(), cleanReceiptUrl, cleanActor.isBlank() ? "system" : cleanActor, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey, authentication);
        if (completedDuringPreflight != null) {
            return completedDuringPreflight;
        }
        ManualCardPaymentPlan plan;
        if (isPendingManualCardPayment(snapshot)) {
            plan = manualCardPaymentPlan(snapshot, null);
            ManualCardPaymentPlan pendingPlan = plan;
            transactionExecutor.requiredNoRollback(() -> {
                markManualCardPaymentPending(pendingPlan, cleanNote, cleanReceiptUrl, cleanActor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey);
                return null;
            });
        } else if (isUnstartedCreatedBankRoute(snapshot)) {
            plan = transactionExecutor.requiredNoRollback(() -> prepareUnstartedManualCardPayment(linkId, orderId, cleanNote, cleanReceiptUrl, cleanActor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey));
        } else if (isSafeHistoricalBankRoute(snapshot)) {
            plan = manualCardPaymentPlan(snapshot, null);
            ManualCardPaymentPlan verifiedPlan = plan;
            transactionExecutor.requiredNoRollback(() -> {
                markManualCardPaymentPending(verifiedPlan, cleanNote, cleanReceiptUrl, cleanActor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey);
                return null;
            });
        } else {
            BankStateObservation observation = observeTbankStateForManualCardPayment(snapshot);
            if (observation == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось получить свежий статус платежа в T-Bank. Ручная оплата не зачислена.");
            }
            plan = transactionExecutor.requiredNoRollback(() -> prepareManualCardPayment(linkId, orderId, observation, cleanNote, cleanReceiptUrl, cleanActor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey));
        }
        CancelReservation reservation = plan.cancelReservation();
        if (reservation != null) {
            PaymentLinkStatus incoming;
            try {
                TbankCancelResponse response = tbankClient.cancel(reservation.runtimeProfile(), new TbankCancelCommand(reservation.paymentId(), reservation.amountKopecks()));
                validateCancelResponse(reservation, response);
                incoming = statusAfterCancel(response.status());
            } catch (RuntimeException failure) {
                recordAmbiguousCancelFailure(reservation, failure);
                throw failure;
            }
            PaymentLinkStatus observedStatus = incoming;
            transactionExecutor.requiredNoRollback(() -> {
                applyCancelObservation(reservation, observedStatus);
                if (observedStatus == PaymentLinkStatus.CANCELED) {
                    markManualCardPaymentPending(plan, cleanNote, cleanReceiptUrl, cleanActor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey);
                }
                return null;
            });
            if (incoming != PaymentLinkStatus.CANCELED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "T-Bank не подтвердил простое закрытие неоплаченной сессии. " + "Получен статус " + incoming + "; ручная оплата не зачислена, нужна сверка.");
            }
        }
        return transactionExecutor.required(() -> applyManualCardPayment(plan, cleanNote, cleanReceiptUrl, cleanActor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey));
    }

    private void reconcileLegacyTerminalBankRoutesForManualCardPayment(Long orderId, Authentication authentication) {
        List<Long> legacyLinkIds = transactionExecutor.required(() -> {
            if (orderId == null || orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
            }
            managerAccessService.requireOrderAccess(orderId, authentication);
            return paymentLinkRepository.findByOrderIdForUpdate(orderId).stream().filter(this::isSelectableTerminalBankRouteForVerification).filter(link -> !isSafeHistoricalBankRoute(link)).map(PaymentLink::getId).filter(Objects::nonNull).toList();
        });
        for (Long legacyLinkId : legacyLinkIds) {
            PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(legacyLinkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Старая T-Bank ссылка изменилась до сверки. Ручная оплата не зачислена."));
            BankStateObservation observation = observeTbankStateForManualCardPayment(snapshot);
            if (observation == null || observation.state() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Не удалось получить свежий статус старой T-Bank ссылки. Ручная оплата не зачислена.");
            }
            transactionExecutor.requiredNoRollback(() -> {
                applyLegacyTerminalObservationForManualCardPayment(orderId, legacyLinkId, observation, authentication);
                return null;
            });
        }
    }

    private void applyLegacyTerminalObservationForManualCardPayment(Long orderId, Long linkId, BankStateObservation observation, Authentication authentication) {
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
        }
        managerAccessService.requireOrderAccess(orderId, authentication);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Старая T-Bank ссылка изменилась до применения сверки."));
        if (!hasOrderBinding(link, orderId) || !sameObservedLink(link, observation) || observation.orderId() == null || !observation.orderId().equals(orderId) || link.getStatus() != observation.status() || link.getAmountKopecks() != observation.amountKopecks() || !normalize(link.getTbankPaymentId()).equals(observation.paymentId()) || !normalize(link.getTbankOrderId()).equals(observation.tbankOrderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Старая T-Bank ссылка изменилась во время сверки. Ручная оплата не зачислена.");
        }
        if (isSafeHistoricalBankRoute(link)) {
            return;
        }
        if (!isSelectableTerminalBankRouteForVerification(link)) {
            throw ambiguousManualCardPaymentRoute();
        }
        applyObservedTbankStateIfCurrent(link, observation, orderId);
        if (hasAuthoritativeProviderTerminalStatus(link)) {
            return;
        }
        String providerStatus = normalize(observation.state().status()).toUpperCase(Locale.ROOT);
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Старая T-Bank ссылка имеет статус " + (providerStatus.isBlank() ? "UNKNOWN" : providerStatus) + ". Ручная оплата не зачислена: платеж может быть активен, оплачен или требовать возврата.");
    }

    private ManualCardPaymentPlan prepareUnstartedManualCardPayment(Long linkId, Long orderId, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey) {
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId) || !isUnstartedCreatedBankRoute(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка изменилась до закрытия. Оплата переводом не зачислена.");
        }
        freezeActualRecipientIntentIfRequired(order, link, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey, context.reason(), receiptUrl, actor.isBlank() ? "system" : actor);
        link.setStatus(PaymentLinkStatus.CANCELED);
        setManualCardPaymentComment(link, manualCardPaymentEvidence(note, receiptUrl, context.mode()));
        link.setLastError(limit(MANUAL_CARD_PAYMENT_PENDING_PREFIX + " local_route_closed; " + actorAuditKey(context.mode()) + "=" + limit(actor.isBlank() ? "admin" : actor, 80), 512));
        paymentLinkRepository.save(link);
        return manualCardPaymentPlan(link, null);
    }

    private ManualCardPaymentPlan prepareManualCardPayment(Long linkId, Long orderId, BankStateObservation observation, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey) {
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId) || !sameObservedLink(link, observation) || observation.orderId() == null || !observation.orderId().equals(orderId) || link.getStatus() != observation.status() || link.getAmountKopecks() != observation.amountKopecks() || !normalize(link.getTbankPaymentId()).equals(observation.paymentId()) || !normalize(link.getTbankOrderId()).equals(observation.tbankOrderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж изменился во время сверки. Ручная оплата не зачислена; обновите журнал.");
        }
        if (link.getPaymentMethod() != PaymentMethod.BANK_FORM && link.getPaymentMethod() != PaymentMethod.SBP_QR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Выбрана не банковская платежная ссылка");
        }
        boolean stableOpenSession = link.getStatus() == PaymentLinkStatus.INITIATED;
        boolean stableSafeTerminalSession = isSafeTerminalBeforeManualCardPayment(link.getStatus());
        if ((!stableOpenSession && !stableSafeTerminalSession) || normalize(link.getTbankPaymentId()).isBlank() || hasBankInitReservation(link) || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Безопасное зачисление доступно только для стабильной открытой T-Bank сессии");
        }
        TbankGetStateResponse state = observation.state();
        String errorCode = normalize(state == null ? null : state.errorCode());
        if (state == null || !state.success() || (!errorCode.isBlank() && !"0".equals(errorCode))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "T-Bank не подтвердил актуальное состояние платежа. Ручная оплата не зачислена.");
        }
        PaymentProfile profile = resolvePaymentProfile(link);
        TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
        if (!normalize(runtimeProfile.terminalKey()).equals(observation.terminalKey()) || !isStateConsistent(link, state, runtimeProfile)) {
            paymentLinkRepository.save(link);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Реквизиты ответа T-Bank не совпали со ссылкой. Ручная оплата не зачислена.");
        }
        String providerStatus = normalize(state.status()).toUpperCase(Locale.ROOT);
        applyObservedTbankStateIfCurrent(link, observation, orderId);
        ManualCardPaymentPlan plan = manualCardPaymentPlan(link, null);
        if (isCancellableUnpaidProviderStatus(providerStatus) && link.getStatus() == PaymentLinkStatus.INITIATED) {
            requireManualCardPaymentLocalEligibility(order, link, orderId);
            // This evidence marker is stored before the remote Cancel call. If
            // that call times out but later reconciliation observes CANCELED,
            // a manager retry can safely resume instead of leaving a permanent
            // NEEDS_RECONCILIATION record.
            freezeActualRecipientIntentIfRequired(order, link, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey, context.reason(), receiptUrl, actor.isBlank() ? "system" : actor);
            setManualCardPaymentComment(link, manualCardPaymentEvidence(note, receiptUrl, context.mode()));
            return manualCardPaymentPlan(link, reserveBankCancel(link, orderId));
        }
        if (("CANCELED".equals(providerStatus) && link.getStatus() == PaymentLinkStatus.CANCELED) || ("REJECTED".equals(providerStatus) && link.getStatus() == PaymentLinkStatus.REJECTED) || ("DEADLINE_EXPIRED".equals(providerStatus) && link.getStatus() == PaymentLinkStatus.EXPIRED)) {
            markManualCardPaymentPending(plan, note, receiptUrl, actor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey);
            return plan;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "T-Bank вернул статус " + (providerStatus.isBlank() ? "UNKNOWN" : providerStatus) + ". Банковский платеж может быть активен, оплачен или требовать возврата; " + "ручная оплата не зачислена.");
    }

    private boolean isCancellableUnpaidProviderStatus(String providerStatus) {
        // FORM_SHOWED means that the customer reached the payment form, not
        // that money was authorized. It is still settled only after T-Bank
        // explicitly accepts Cancel and returns CANCELED.
        return "NEW".equals(providerStatus) || "FORM_SHOWED".equals(providerStatus);
    }

    private AdminPaymentLinkResponse preflightManualCardPaymentRecipientIntent(Long linkId, Long orderId, long amountKopecks, String reason, String receiptUrl, String actor, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey, Authentication authentication) {
        return transactionExecutor.required(() -> {
            Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
            managerAccessService.requireOrderAccess(orderId, authentication);
            ensureOrderNotCoveredByActiveCommonInvoice(orderId);
            PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
            if (!hasOrderBinding(link, orderId) || link.getAmountKopecks() != amountKopecks) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Платёжный источник заказа изменился");
            }
            if (isCompletedManualCardPayment(link)) {
                requireCompletedManualCardPaymentReplay(link, amountKopecks, requestedRecipientKey, requestedRecipientType, requestedRecipientProfileId, reason, receiptUrl);
                return toAdminResponse(link);
            }
            requireManualCardPaymentLocalEligibility(order, link, orderId);
            if (actor.isBlank() || actor.length() > 150) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный исполнитель операции");
            }
            requireHistoricalPreCutoverManualCardSelectionIfNeeded(link, requestedRecipientKey);
            if (!isHistoricalPreCutoverManualCardSettlement(link, requestedRecipientKey)) {
                actualPaymentAttributionService.manualCardPaymentContext(order, link);
            }
            return null;
        });
    }

    private void requireCompletedManualCardPaymentReplay(PaymentLink link, long requestedAmountKopecks, String requestedRecipientKey, ContractorRecipientType requestedType, Long requestedProfileId, String reason, String receiptUrl) {
        manualCardRoutePolicy.requireCompletedManualCardPaymentReplay(link, requestedAmountKopecks, requestedRecipientKey, requestedType, requestedProfileId, reason, receiptUrl);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void freezeActualRecipientIntentIfRequired(Order order, PaymentLink link, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey, String reason, String receiptUrl, String actor) {
        requireHistoricalPreCutoverManualCardSelectionIfNeeded(link, requestedRecipientKey);
        if (isHistoricalPreCutoverManualCardSettlement(link, requestedRecipientKey)) {
            return;
        }
        boolean required = link.getManualActualRecipientFrozenAt() != null || link.getManualSource() == ManualPaymentSource.MANUAL_TASK || actualPaymentAttributionService.actualRecipientAccountingEnabled();
        if (!required) {
            boolean legacyOwnerShape = requestedRecipientType == null && requestedRecipientProfileId == null;
            legacyOwnerShape = legacyOwnerShape || (requestedRecipientType == ContractorRecipientType.OWNER && requestedRecipientProfileId == null);
            if (!legacyOwnerShape) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Учет фактических получателей выключен; получателем может быть только владелец");
            }
            return;
        }
        if (requestedRecipientType == null && normalize(requestedRecipientKey).isBlank()) {
            throw ManualPaymentTaskRouteErrors.actualRecipientRequired();
        }
        actualPaymentAttributionService.freezePaymentLinkRecipientIntent(order, link, requestedRecipientKey, requestedRecipientType, requestedRecipientProfileId, reason, receiptUrl, actor);
    }

    /**
     * Repeated under Order + target link + every order-link lock immediately before remote Cancel.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void requireManualCardPaymentLocalEligibility(Order order, PaymentLink link, Long orderId) {
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        if (!canApplyOrderPaymentNow(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ еще не выполнен полностью; ручную оплату нельзя зачислить");
        }
        if (isAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма заказа изменилась до сверки T-Bank. Ручная оплата не зачислена.");
        }
        if (orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже имеет признаки оплаты. Автоматическое повторное зачисление заблокировано.");
        }
        boolean competingPayment = paymentLinkRepository.findByOrderIdForUpdate(orderId).stream().filter(candidate -> !sameLinkId(link, candidate)).anyMatch(this::isCompetingManualCardPaymentRoute);
        if (competingPayment) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа найден другой активный или подтвержденный способ оплаты. Нужна ручная сверка.");
        }
    }

    private void markManualCardPaymentPending(ManualCardPaymentPlan plan, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey) {
        Order order = orderRepository.findByIdForCounterUpdate(plan.orderId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
        managerAccessService.requireOrderAccess(plan.orderId(), authentication);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(plan.linkId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        requireManualCardPlanBinding(link, plan);
        if (!isSafeHistoricalBankRoute(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "T-Bank сессия не имеет подтвержденного безопасного завершения. Ручная оплата не зачислена.");
        }
        freezeActualRecipientIntentIfRequired(order, link, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey, context.reason(), receiptUrl, actor.isBlank() ? "system" : actor);
        setManualCardPaymentComment(link, manualCardPaymentEvidence(note, receiptUrl, context.mode()));
        link.setLastError(limit(MANUAL_CARD_PAYMENT_PENDING_PREFIX + " " + actorAuditKey(context.mode()) + "=" + limit(actor.isBlank() ? "admin" : actor, 80), 512));
        paymentLinkRepository.save(link);
    }

    private AdminPaymentLinkResponse applyManualCardPayment(ManualCardPaymentPlan plan, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey) {
        Order order = orderRepository.findByIdForCounterUpdate(plan.orderId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
        managerAccessService.requireOrderAccess(plan.orderId(), authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(plan.orderId());
        paymentInvoiceRetryScheduler.assertPaymentAutomationMutable(plan.orderId());
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(plan.linkId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        requireManualCardPlanBinding(link, plan);
        if (isCompletedManualCardPayment(link)) {
            requireCompletedManualCardPaymentReplay(link, plan.amountKopecks(), requestedRecipientKey, requestedRecipientType, requestedRecipientProfileId, context.reason(), receiptUrl);
            return toAdminResponse(link);
        }
        if (!isPendingManualCardPayment(link) || !isSafeHistoricalBankRoute(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Закрытие T-Bank сессии не подтверждено. Ручная оплата не зачислена.");
        }
        if (!canApplyOrderPaymentNow(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ еще не выполнен полностью; ручную оплату нельзя зачислить");
        }
        if (isAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма заказа изменилась после сверки T-Bank. Ручная оплата не зачислена.");
        }
        if (orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже имеет признаки оплаты. Автоматическое повторное зачисление заблокировано.");
        }
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(plan.orderId());
        boolean competingPayment = links.stream().filter(candidate -> !sameLinkId(link, candidate)).anyMatch(this::isCompetingManualCardPaymentRoute);
        if (competingPayment) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У заказа найден другой активный или подтвержденный способ оплаты. Нужна ручная сверка.");
        }
        LocalDateTime now = LocalDateTime.now();
        PaymentLink manualEvidence = manualCardPaymentEvidenceLink(link, order, note, receiptUrl, actor, now, context.mode());
        manualEvidence = paymentLinkRepository.saveAndFlush(manualEvidence);
        try {
            // The check must belong to the actual cash evidence, not to the
            // historical bank route that was closed before the card transfer.
            handlePaymentStatusWithoutPrematureRepeat(order, manualEvidence.getId());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось зачислить ручную оплату заказа", e);
        }
        setManualCardPaymentComment(link, manualCardPaymentAudit(note, receiptUrl, context.mode()));
        link.setManualConfirmedAt(null);
        link.setManualConfirmedBy(null);
        link.setConfirmedAmountKopecks(null);
        link.setReceiptStatus(null);
        link.setLastError(limit(MANUAL_CARD_PAYMENT_COMPLETED_PREFIX + " evidence_token=" + manualEvidence.getToken(), 512));
        paymentLinkRepository.saveAndFlush(link);
        boolean historicalPreCutoverManualCard = isHistoricalPreCutoverManualCardSettlement(link, requestedRecipientKey);
        if (link.getManualActualRecipientFrozenAt() != null) {
            actualPaymentAttributionService.recordPaymentLinkFinalAttribution(order, link, manualEvidence);
            notifyManualPaymentRecipientAfterCommit(manualEvidence);
            boolean toTask = link.getManualActualCashDestinationKind() == com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind.MANUAL_PAYMENT_TASK;
            String selectedKey = toTask ? ManualPaymentTaskLedgerService.candidateKey(link.getManualActualTaskId(), link.getManualActualTaskGeneration()) : link.getManualActualRecipientType() == ContractorRecipientType.OWNER ? "OWNER" : "PROFILE:" + link.getManualActualRecipientProfileId();
            taskReceiptIntegrationService.settle(link, selectedKey, toTask ? manualEvidence.getAmountKopecks() : 0L, "TASK:SETTLE:PAYMENT_LINK:" + link.getId(), link.getManualActualActor(), link.getManualActualReason());
        } else if (!historicalPreCutoverManualCard && actualPaymentAttributionService.actualRecipientAccountingEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Режим учёта изменился после закрытия банковской сессии; повторите операцию с выбором получателя");
        }
        closeManualPaymentAutomationAfterCommit(order);
        log.warn("Order paid by manual card transfer after bank-route reconciliation: orderId={}, linkId={}, actor={}", plan.orderId(), plan.linkId(), actor);
        return toAdminResponse(link);
    }

    private AdminPaymentLinkResponse applyDirectManualPayment(Long linkId, Long orderId, long amountKopecks, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedType, Long requestedProfileId, String requestedRecipientKey) {
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        paymentInvoiceRetryScheduler.assertPaymentAutomationMutable(orderId);
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!hasOrderBinding(link, orderId) || (!isDirectManualAttributionRoute(link) && !isRecoverableExpiredManualRoute(link)) || link.getAmountKopecks() != amountKopecks) {
            throw ManualPaymentTaskRouteErrors.stale();
        }
        boolean historicalPreCutoverManualCard = isHistoricalPreCutoverManualCardSettlement(link, requestedRecipientKey);
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            requireCompletedManualCardPaymentReplay(link, amountKopecks, requestedRecipientKey, requestedType, requestedProfileId, context.reason(), receiptUrl);
            if (!historicalPreCutoverManualCard) {
                settleTaskReceipt(link);
            }
            return toAdminResponse(link);
        }
        validateManualConfirmable(link);
        validateAmountCurrentForManualConfirm(link);
        freezeActualRecipientIntentIfRequired(order, link, requestedType, requestedProfileId, requestedRecipientKey, context.reason(), receiptUrl, actor.isBlank() ? "system" : actor);
        if (orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже имеет признаки оплаты. Повторное зачисление заблокировано.");
        }
        boolean updated = false;
        try {
            if (canApplyOrderPaymentNow(order)) {
                updated = handlePaymentStatusWithoutPrematureRepeat(order, link.getId());
            }
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось зачислить ручную оплату", failure);
        }
        LocalDateTime now = LocalDateTime.now();
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(now);
        link.setManualConfirmedAt(now);
        link.setManualConfirmedBy(limit(actor, 160));
        link.setConfirmedAmountKopecks(amountKopecks);
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        setManualCardPaymentComment(link, manualCardPaymentAudit(note, receiptUrl, context.mode()));
        link.setLastError(canApplyOrderPaymentNow(order) ? null : PREPAID_WAITING_ORDER_COMPLETION);
        prepareSuccessNotificationRetry(link);
        paymentLinkRepository.saveAndFlush(link);
        if (!historicalPreCutoverManualCard) {
            settleTaskReceipt(link);
            actualPaymentAttributionService.recordPaymentLinkFinalAttribution(order, link, link);
            notifyManualPaymentRecipientAfterCommit(link);
            manualPaymentTaskService.completeIfConfirmedTargetReached(link.getManualPaymentTask());
        }
        if (updated)
            cancelBadReviewAutoBanAfterCommit(order, "Ручная оплата подтверждена");
        syncCommonInvoiceOrderPayment(link, "Ручная оплата заказа");
        closeManualPaymentAutomationAfterCommit(order);
        return toAdminResponse(link);
    }

    private void settleTaskReceipt(PaymentLink link) {
        if (link == null || link.getManualSource() != ManualPaymentSource.MANUAL_TASK)
            return;
        boolean selectedTask = link.getManualActualCashDestinationKind() == ContractorCashDestinationKind.MANUAL_PAYMENT_TASK;
        String selectedKey = selectedTask ? ManualPaymentTaskLedgerService.candidateKey(link.getManualActualTaskId(), link.getManualActualTaskGeneration()) : link.getManualActualRecipientType() == ContractorRecipientType.OWNER ? "OWNER" : "PROFILE:" + link.getManualActualRecipientProfileId();
        taskReceiptIntegrationService.settle(link, selectedKey, selectedTask ? link.getAmountKopecks() : 0L, "TASK:SETTLE:PAYMENT_LINK:" + link.getId(), link.getManualActualActor(), link.getManualActualReason());
    }

    private void requireManualCardPlanBinding(PaymentLink link, ManualCardPaymentPlan plan) {
        if (!hasOrderBinding(link, plan.orderId()) || !normalize(link.getTbankPaymentId()).equals(plan.paymentId()) || !normalize(link.getTbankOrderId()).equals(plan.tbankOrderId()) || link.getAmountKopecks() != plan.amountKopecks() || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж изменился после сверки. Ручная оплата не зачислена.");
        }
    }

    private ManualCardPaymentPlan manualCardPaymentPlan(PaymentLink link, CancelReservation reservation) {
        if (link == null || link.getOrder() == null || link.getOrder().getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден");
        }
        return new ManualCardPaymentPlan(link.getId(), link.getOrder().getId(), normalize(link.getTbankPaymentId()), normalize(link.getTbankOrderId()), link.getAmountKopecks(), reservation);
    }

    private boolean isPendingManualCardPayment(PaymentLink link) {
        return manualCardRoutePolicy.isPendingManualCardPayment(link);
    }

    private boolean isCompletedManualCardPayment(PaymentLink link) {
        return manualCardRoutePolicy.isCompletedManualCardPayment(link);
    }

    private PaymentLink selectManualCardPaymentRoute(List<PaymentLink> orderLinks) {
        return manualCardRoutePolicy.selectManualCardPaymentRoute(orderLinks);
    }

    private boolean isDirectManualAttributionRoute(PaymentLink link) {
        return manualCardRoutePolicy.isDirectManualAttributionRoute(link);
    }

    /**
     * A client can report a transfer after an issued manual instruction was
     * retired by TTL or by a later payable correction. Reusing such a row is
     * safe only when it has no payment evidence and its frozen amount is once
     * again exactly the current payable amount. The manager still has to pick
     * the actual recipient before the order is credited.
     */
    private boolean isRecoverableExpiredManualRoute(PaymentLink link) {
        return manualConfirmationWorkflow.isRecoverableExpiredManualRoute(link);
    }

    private ResponseStatusException ambiguousManualCardPaymentRoute() {
        return manualCardRoutePolicy.ambiguousManualCardPaymentRoute();
    }

    private boolean isSelectableTerminalBankRouteForVerification(PaymentLink link) {
        return manualCardRoutePolicy.isSelectableTerminalBankRouteForVerification(link);
    }

    private boolean isSafeHistoricalBankRoute(PaymentLink link) {
        return manualCardRoutePolicy.isSafeHistoricalBankRoute(link);
    }

    private boolean hasAuthoritativeProviderTerminalStatus(PaymentLink link) {
        return manualCardRoutePolicy.hasAuthoritativeProviderTerminalStatus(link);
    }

    private boolean isCompetingManualCardPaymentRoute(PaymentLink link) {
        return manualCardRoutePolicy.isCompetingManualCardPaymentRoute(link);
    }

    private boolean isUnstartedCreatedBankRoute(PaymentLink link) {
        return manualCardRoutePolicy.isUnstartedCreatedBankRoute(link);
    }

    private boolean isActiveOrAmbiguousTochkaRouteForManualCardPayment(PaymentLink link) {
        return manualCardRoutePolicy.isActiveOrAmbiguousTochkaRouteForManualCardPayment(link);
    }

    private boolean isSafeTerminalBeforeManualCardPayment(PaymentLinkStatus status) {
        return manualCardRoutePolicy.isSafeTerminalBeforeManualCardPayment(status);
    }

    private String manualCardPaymentAudit(String note, String receiptUrl, ManualCardPaymentMode mode) {
        return limit(manualCardPaymentAuditPrefix(mode) + ": " + normalize(note), 255);
    }

    private String manualCardPaymentEvidence(String note, String receiptUrl, ManualCardPaymentMode mode) {
        return limit(manualCardPaymentEvidencePrefix(mode) + ": " + normalize(note), 255);
    }

    private String manualCardPaymentAuditPrefix(ManualCardPaymentMode mode) {
        return mode == ManualCardPaymentMode.MANAGER_REPORTED ? MANAGER_REPORTED_CARD_PAYMENT_AUDIT_PREFIX : MANUAL_CARD_PAYMENT_AUDIT_PREFIX;
    }

    private String manualCardPaymentEvidencePrefix(ManualCardPaymentMode mode) {
        return mode == ManualCardPaymentMode.MANAGER_REPORTED ? MANAGER_REPORTED_CARD_PAYMENT_EVIDENCE_PREFIX : MANUAL_CARD_PAYMENT_EVIDENCE_PREFIX;
    }

    private void setManualCardPaymentComment(PaymentLink link, String comment) {
        if (link == null) {
            return;
        }
        if (link.getManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE) {
            // Contractor routes keep recipient/payment PII only in the encrypted
            // allocation snapshot/evidence link. The legacy payment_links
            // plaintext fields are protected by ck_payment_links_contractor_pii_blank.
            link.setManualComment(null);
            return;
        }
        link.setManualComment(comment);
    }

    private String actorAuditKey(ManualCardPaymentMode mode) {
        return mode == ManualCardPaymentMode.MANAGER_REPORTED ? "reported_by" : "checked_by";
    }

    private PaymentLink manualCardPaymentEvidenceLink(PaymentLink bankLink, Order order, String note, String receiptUrl, String actor, LocalDateTime now, ManualCardPaymentMode mode) {
        PaymentLink evidence = new PaymentLink();
        evidence.setToken(newToken());
        evidence.setOrder(order);
        evidence.setContractorEvidenceOriginalLinkId(bankLink.getId());
        evidence.setAmountKopecks(bankLink.getAmountKopecks());
        evidence.setConfirmedAmountKopecks(bankLink.getAmountKopecks());
        evidence.setDescription(limit("Оплата переводом на карту: " + description(order), 140));
        evidence.setStatus(PaymentLinkStatus.CONFIRMED);
        evidence.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        evidence.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        evidence.setManualComment(manualCardPaymentAudit(note, receiptUrl, mode));
        evidence.setManualConfirmedAt(now);
        evidence.setManualConfirmedBy(limit(actor.isBlank() ? "admin" : actor, 160));
        evidence.setPaidAt(now);
        evidence.setReceiptStatus(PaymentReceiptStatus.PENDING);
        evidence.setPaymentSuccessNotificationRetryEligible(false);
        evidence.setExpiresAt(now.plus(properties.getLinkTtl()));
        copyManualActualRecipientContext(bankLink, evidence);
        return evidence;
    }

    private void copyManualActualRecipientContext(PaymentLink source, PaymentLink target) {
        if (source == null || target == null) {
            return;
        }
        target.setManualActualAccountingMode(source.getManualActualAccountingMode());
        target.setManualActualOriginalCashDestinationKind(source.getManualActualOriginalCashDestinationKind());
        target.setManualActualOriginalAllocationId(source.getManualActualOriginalAllocationId());
        target.setManualActualClientFacingAllocationId(source.getManualActualClientFacingAllocationId());
        target.setManualActualOriginalRecipientType(source.getManualActualOriginalRecipientType());
        target.setManualActualOriginalRecipientProfileId(source.getManualActualOriginalRecipientProfileId());
        target.setManualActualOriginalRecipientUserId(source.getManualActualOriginalRecipientUserId());
        target.setManualActualOriginalRecipientNameSnapshot(source.getManualActualOriginalRecipientNameSnapshot());
        target.setManualActualOriginalTaskId(source.getManualActualOriginalTaskId());
        target.setManualActualOriginalTaskGeneration(source.getManualActualOriginalTaskGeneration());
        target.setManualActualOriginalTaskTargetKind(source.getManualActualOriginalTaskTargetKind());
        target.setManualActualCashDestinationKind(source.getManualActualCashDestinationKind());
        target.setManualActualRecipientType(source.getManualActualRecipientType());
        target.setManualActualRecipientProfileId(source.getManualActualRecipientProfileId());
        target.setManualActualRecipientUserId(source.getManualActualRecipientUserId());
        target.setManualActualRecipientNameSnapshot(source.getManualActualRecipientNameSnapshot());
        target.setManualActualTaskId(source.getManualActualTaskId());
        target.setManualActualTaskGeneration(source.getManualActualTaskGeneration());
        target.setManualActualTaskTargetKind(source.getManualActualTaskTargetKind());
        target.setManualActualCurrentWorkerId(source.getManualActualCurrentWorkerId());
        target.setManualActualCurrentManagerId(source.getManualActualCurrentManagerId());
        target.setManualActualReason(source.getManualActualReason());
        target.setManualActualReceiptUrl(source.getManualActualReceiptUrl());
        target.setManualActualActor(source.getManualActualActor());
        target.setManualActualRecipientFrozenAt(source.getManualActualRecipientFrozenAt());
    }

    private void closeManualPaymentAutomationAfterCommit(Order order) {
        Long orderId = order == null ? null : order.getId();
        Runnable cleanup = () -> {
            try {
                paymentInvoiceRetryScheduler.cancelPaymentAutomation(orderId, "Заказ оплачен переводом на карту; T-Bank сессия закрыта");
            } catch (RuntimeException e) {
                log.error("Не удалось закрыть платежные расписания после ручной оплаты orderId={}", orderId, e);
            }
        };
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    cleanup.run();
                }
            }
        });
    }

    private void cancelBadReviewAutoBanAfterCommit(Order order, String reason) {
        settlementService.cancelBadReviewAutoBanAfterCommit(order, reason);
    }

    private CancelReservation reserveBankCancel(PaymentLink link, Long orderId) {
        return cancellationWorkflow.reserveBankCancel(link, orderId);
    }

    private AdminPaymentLinkResponse applyCancelObservation(CancelReservation reservation, PaymentLinkStatus incoming) {
        return cancellationWorkflow.applyCancelObservation(reservation, incoming);
    }

    private void recordAmbiguousCancelFailure(CancelReservation reservation, RuntimeException failure) {
        cancellationWorkflow.recordAmbiguousCancelFailure(reservation, failure);
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return paymentPresenter.hasBankCancelReservation(link);
    }

    private boolean isStateConsistent(PaymentLink link, TbankGetStateResponse state, TbankPaymentProfile runtimeProfile) {
        return bankObservationApplication.isStateConsistent(link, state, runtimeProfile);
    }

    private boolean canApplyOrderPaymentNow(Order order) {
        return settlementService.canApplyOrderPaymentNow(order);
    }

    private boolean handlePaymentStatusWithoutPrematureRepeat(Order order, Long paymentLinkId) throws Exception {
        return settlementService.handlePaymentStatusWithoutPrematureRepeat(order, paymentLinkId);
    }

    private void syncCommonInvoiceOrderPayment(PaymentLink link, String reason) {
        settlementService.syncCommonInvoiceOrderPayment(link, reason);
    }

    private void prepareSuccessNotificationRetry(PaymentLink link) {
        settlementService.prepareSuccessNotificationRetry(link);
    }

    private void notifyManualPaymentRecipientAfterCommit(PaymentLink link) {
        manualPaymentRecipientTelegramNotificationService.notifyAfterCommit(link);
    }

    private void notifyManualPaymentRecipientAfterCommit(Long paymentLinkId) {
        manualPaymentRecipientTelegramNotificationService.notifyAfterCommit(paymentLinkId);
    }

    private void validateCancelResponse(CancelReservation reservation, TbankCancelResponse response) {
        cancellationWorkflow.validateCancelResponse(reservation, response);
    }

    private boolean sameLinkId(PaymentLink left, PaymentLink right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId != null && leftId.equals(rightId);
    }

    private boolean isAmountChanged(PaymentLink link) {
        return lifecycleService.isAmountChanged(link);
    }

    private void validateManualConfirmable(PaymentLink link) {
        manualConfirmationWorkflow.validateManualConfirmable(link);
    }

    private void validateAmountCurrentForManualConfirm(PaymentLink link) {
        manualConfirmationWorkflow.validateAmountCurrentForManualConfirm(link);
    }

    private AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        return paymentPresenter.toAdminResponse(link);
    }

    private PaymentProfile resolvePaymentProfile(PaymentLink link) {
        return bankObservations.resolvePaymentProfile(link);
    }

    private TbankPaymentProfile runtimeProfileForLink(PaymentProfile profile, PaymentLink link) {
        return bankObservations.runtimeProfileForLink(profile, link);
    }

    private PaymentLinkStatus statusAfterCancel(String status) {
        return cancellationWorkflow.statusAfterCancel(status);
    }

    private String description(Order order) {
        return preparationWorkflow.description(order);
    }

    private String newToken() {
        return preparationWorkflow.newToken();
    }

    String validatedReceiptUrl(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка на чек не должна превышать 1024 символа");
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = normalize(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || normalize(uri.getHost()).isBlank() || uri.getUserInfo() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка на чек должна быть безопасной ссылкой http/https");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка на чек имеет неверный формат");
        }
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }

    record ManualCardPaymentPlan(Long linkId, Long orderId, String paymentId, String tbankOrderId, long amountKopecks, CancelReservation cancelReservation) {
    }

    record OrderManualCardRoute(Long linkId, long amountKopecks) {
    }

    record ManualCardPaymentContext(ManualCardPaymentMode mode, String reason) {
    }

    enum ManualCardPaymentMode {

        OWNER_VERIFIED, MANAGER_REPORTED
    }
}
