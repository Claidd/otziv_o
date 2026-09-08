package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.PaymentLinkPreparationWorkflow.REUSABLE_STATUSES;
import static com.hunt.otziv.payments.service.PaymentLinkPreparationWorkflow.ROUTE_CHANGE_CURRENT_STATUSES;
import static com.hunt.otziv.payments.service.ManualPaymentConfirmationWorkflow.PAID_STATUSES;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.ProviderStateObservation;

@Service
@Slf4j
@RequiredArgsConstructor
/** Reconciles payment links with their order's amount, completion and expiry state. */
public class OrderPaymentLinkWorkflow {

    private final PaymentLinkAmountPolicy amountPolicy;

    private final PaymentLinkLifecycleService lifecycleService;

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final BankObservationApplicationService bankObservationApplication;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    static final Set<PaymentLinkStatus> ORDER_RECONCILIATION_CANDIDATE_STATUSES = Set.of(PaymentLinkStatus.CREATED, PaymentLinkStatus.INITIATED, PaymentLinkStatus.AUTHORIZED, PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED, PaymentLinkStatus.NEEDS_RECONCILIATION);

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    @Transactional
    public int expireStaleLinksForOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        expireStaleManualLinks(LocalDateTime.now());
        List<PaymentLink> links = paymentLinkRepository.findByOrder_IdAndStatusIn(orderId, REUSABLE_STATUSES);
        int expired = 0;
        for (PaymentLink link : links) {
            if (expireIfAmountChanged(link)) {
                expired++;
            }
        }
        return expired;
    }

    /**
     * Fail-closed preflight for a task price entering or leaving an order.
     * Only a route with no client/bank evidence can be retired. The caller's
     * transaction keeps the order and link locks until the task, reward and
     * payable amount have changed together.
     */
    @Transactional
    public int retireOpenLinksBeforePayableChange(Long orderId, String reason) {
        if (orderId == null || orderId <= 0) {
            return 0;
        }
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
        }
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        int retired = 0;
        for (PaymentLink link : links) {
            PaymentLinkStatus status = link.getStatus();
            if (status == PaymentLinkStatus.REJECTED || status == PaymentLinkStatus.CANCELED || status == PaymentLinkStatus.REVERSED || status == PaymentLinkStatus.REFUNDED || status == PaymentLinkStatus.EXPIRED || status == PaymentLinkStatus.FAILED) {
                continue;
            }
            if (canRetireStaleLink(link)) {
                String retirementReason = limit(normalize(reason).isBlank() ? "Состав суммы заказа изменен до начала платежа" : normalize(reason), 512);
                // Release the source-bound task ledger/reservation while the
                // original route is still locked and identifiable. Merely
                // expiring the public link would otherwise leave a phantom
                // MANUAL_TASK commitment after the order amount is reduced.
                taskReceiptIntegrationService.release(link, retirementReason);
                link.setStatus(PaymentLinkStatus.EXPIRED);
                link.setLastError(retirementReason);
                paymentLinkRepository.save(link);
                contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
                retired++;
                continue;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "По счету уже есть действие клиента, банка или состояние сверки. " + "Сначала завершите ручную сверку платежа");
        }
        return retired;
    }

    /**
     * Refreshes the bank state of the current payment before an explicit
     * automation retry. A payment that has already finished is applied through
     * the normal bank-status path; an unstarted stale link may be retired. A
     * genuinely active payment is deliberately left untouched so the retry
     * cannot create a duplicate charge.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcileActiveOrder(Long orderId) {
        reconcileActiveLinkForOrder(orderId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentLinkReconcileResult reconcileActiveLinkForOrder(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return new PaymentLinkReconcileResult(null, null, null, false);
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<PaymentLink> candidate = paymentLinkRepository.findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(orderId, ORDER_RECONCILIATION_CANDIDATE_STATUSES, now);
        if (candidate.isEmpty()) {
            return new PaymentLinkReconcileResult(null, null, null, false);
        }
        PaymentLink candidateLink = candidate.get();
        Long linkId = candidateLink.getId();
        PaymentLink snapshot = shouldObserveBankState(candidateLink) && linkId != null ? paymentLinkRepository.findByIdWithOrder(linkId).orElse(candidateLink) : candidateLink;
        ProviderStateObservation observation = observeBankState(snapshot);
        return transactionExecutor.required(() -> reconcileActiveLinkLocked(orderId, linkId, observation));
    }

    /**
     * Reconciles an ordinary unpaid payment link after the order payable has
     * changed. No provider request is made here. A pristine link is retired so
     * its stable public URL can resolve to a freshly routed link with the new
     * amount on the next open. A link with client/bank evidence is quarantined
     * instead of being silently rewritten. Paid links are immutable.
     */
    @Transactional
    public boolean refreshLinkedOrderAmount(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return false;
        }
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        long payableKopecks = amountKopecks(payableSum(order));
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        boolean linked = false;
        LocalDateTime now = LocalDateTime.now();
        for (PaymentLink link : links) {
            if (link == null || !ROUTE_CHANGE_CURRENT_STATUSES.contains(link.getStatus())) {
                continue;
            }
            linked = true;
            if (PAID_STATUSES.contains(link.getStatus()) || link.getPaidAt() != null || link.getConfirmedAmountKopecks() != null || link.getAmountKopecks() == payableKopecks) {
                continue;
            }
            if (canRetireStaleLink(link)) {
                expireIfAmountChanged(link);
                if (link.getContractorAllocationId() != null) {
                    contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
                }
                continue;
            }
            link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
            link.setExpiresAt(now);
            link.setLastError(limit("Сумма заказа изменилась: было " + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString() + " руб., стало " + amountRubles(payableKopecks).stripTrailingZeros().toPlainString() + " руб.; есть признаки платежного действия, нужна сверка", 512));
            paymentLinkRepository.save(link);
        }
        return linked;
    }

    private PaymentLinkReconcileResult reconcileActiveLinkLocked(Long orderId, Long linkId, ProviderStateObservation observation) {
        if (orderRepository.findByIdForCounterUpdate(orderId).isEmpty()) {
            return new PaymentLinkReconcileResult(linkId, null, null, false);
        }
        PaymentLink link = linkId == null ? null : paymentLinkRepository.findByIdForUpdate(linkId).orElse(null);
        if (!hasOrderBinding(link, orderId)) {
            return new PaymentLinkReconcileResult(linkId, null, null, false);
        }
        PaymentLinkStatus before = link.getStatus();
        applyObservedBankStateIfCurrent(link, observation, orderId);
        expireIfPastDue(link);
        if (REUSABLE_STATUSES.contains(link.getStatus()) && expireIfAmountChanged(link)) {
            link = paymentLinkRepository.findById(link.getId()).orElse(link);
        }
        return new PaymentLinkReconcileResult(link.getId(), before, link.getStatus(), before != link.getStatus());
    }

    private boolean canRetireStaleLink(PaymentLink link) {
        return lifecycleService.canRetireStaleLink(link);
    }

    private void expireStaleManualLinks(LocalDateTime now) {
        preparationWorkflow.expireStaleManualLinks(now);
    }

    private boolean shouldObserveBankState(PaymentLink link) {
        return bankObservations.shouldObserveBankState(link);
    }

    private ProviderStateObservation observeBankState(PaymentLink link) {
        return bankObservations.observeBankState(link);
    }

    private void applyObservedBankStateIfCurrent(PaymentLink link, ProviderStateObservation observation, Long lockedOrderId) {
        bankObservationApplication.applyObservedBankStateIfCurrent(link, observation, lockedOrderId);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    private void expireIfPastDue(PaymentLink link) {
        lifecycleService.expireIfPastDue(link);
    }

    private boolean expireIfAmountChanged(PaymentLink link) {
        return lifecycleService.expireIfAmountChanged(link);
    }

    private BigDecimal payableSum(Order order) {
        return amountPolicy.payableSum(order);
    }

    private long amountKopecks(BigDecimal amount) {
        return amountPolicy.amountKopecks(amount);
    }

    private BigDecimal amountRubles(long amountKopecks) {
        return paymentPresenter.amountRubles(amountKopecks);
    }

    public record PaymentLinkReconcileResult(Long linkId, PaymentLinkStatus statusBefore, PaymentLinkStatus statusAfter, boolean changed) {
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }
}
