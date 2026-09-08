package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoicePaymentRouteWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceManualPaymentWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceTochkaReconciliationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRefResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonBillingAccountCompanyRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.api.NextOrderRequests;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Owns order membership, payable changes and attachment readiness under the existing canonical locks. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceMembershipWorkflow implements com.hunt.otziv.common_billing.api.PublicationInvoiceCompletion {

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceDetailsAssembler invoiceDetailsAssembler;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoicePresenter invoicePresenter;

    public static final String STATUS_WAITING_COMMON_INVOICE = CommonInvoiceSettlementService.STATUS_WAITING_COMMON_INVOICE;

    private final CommonInvoiceSettlementService settlementService;

    static final Set<CommonInvoiceStatus> ATTACHABLE_INVOICE_STATUSES = Set.of(CommonInvoiceStatus.COLLECTING, CommonInvoiceStatus.READY);

    static final Set<String> DELETION_DETACH_BLOCKING_PAYMENT_REF_STATUSES = Set.of(PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCEL_FAILED_FINAL, PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CURRENT);

    static final String BAD_REVIEW_SUPPLEMENT_REQUIRED = "bad_review_supplement_required:";

    static final String PAYABLE_CHANGE_REQUIRES_SUPPLEMENT = "payable_change_requires_supplement:";

    static final String COMMON_INVOICE_ROUTE_ATTACHED_PREFIX = "common_invoice_route_attached; ";

    static final String MANUAL_PAYMENT_ABSENT_VERIFIED_PREFIX = "manual_payment_absent_verified";

    private final EntityManager entityManager;

    private final CommonBillingAccountCompanyRepository accountCompanyRepository;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final OrderRepository orderRepository;

    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    private final NextOrderRequests nextOrderRequests;

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderStatusService orderStatusService;

    private final BadReviewTaskService badReviewTaskService;

    private final AppSettingService appSettingService;

    private final CommonInvoicePublicationBlockerService publicationBlockerService;

    @Transactional
    public boolean attachOrderIfNeeded(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        Long companyId = orderRepository.findCompanyIdByOrderId(order.getId()).orElse(null);
        if (companyId == null) {
            return false;
        }
        if (invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(order.getId()).isPresent()) {
            return true;
        }
        Optional<CommonBillingAccount> account = enabledAccountForCompany(companyId);
        if (account.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = attachOrderToCurrentInvoice(account.get(), order.getId(), companyId);
        log.info("Order {} attached to common invoice {} for account {}", order.getId(), item.getInvoice().getId(), account.get().getId());
        return true;
    }

    @Transactional
    public boolean refreshLinkedOrderAmount(Long orderId) {
        if (orderId == null) {
            return false;
        }
        Optional<CommonInvoiceOrder> optionalItem = invoiceOrderRepository.findByOrderIdWithInvoice(orderId);
        if (optionalItem.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = optionalItem.get();
        CommonInvoice invoice = item.getInvoice();
        if (item.isPaid() || invoice.getStatus() == CommonInvoiceStatus.PAID || invoice.getStatus() == CommonInvoiceStatus.BAN || invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            return true;
        }
        if (invoice.getStatus() != CommonInvoiceStatus.COLLECTING || invoice.getSentAt() != null || invoice.getClientReportedAt() != null || hasFrozenCommonPaymentRoute(invoice)) {
            CommonInvoiceStatus previous = invoice.getStatus();
            invoice.setPreviousStatus(previous == null ? null : previous.name());
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(PAYABLE_CHANGE_REQUIRES_SUPPLEMENT + " source_invoice=" + invoice.getId() + ";order=" + orderId + ";previous=" + (previous == null ? "unknown" : previous.name()), 512));
            invoiceRepository.save(invoice);
            return true;
        }
        Long payable = payableKopecksOrMarkAttention(invoice, item.getOrder());
        if (payable == null) {
            return true;
        }
        item.setAmountKopecks(payable);
        invoiceOrderRepository.save(item);
        List<CommonInvoiceOrder> currentItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        recalculateInvoice(invoice, currentItems);
        promoteBadReviewSuccessorIfReady(invoice, currentItems);
        return true;
    }

    @Transactional
    public boolean completePublishedOrderIntoCommonInvoice(Order order) {
        if (order == null || order.getId() == null) {
            return false;
        }
        attachOrderIfNeeded(order);
        Optional<CommonInvoiceOrder> optionalItem = invoiceOrderRepository.findByOrderIdWithInvoice(order.getId());
        if (optionalItem.isEmpty()) {
            return false;
        }
        CommonInvoiceOrder item = optionalItem.get();
        CommonInvoice invoice = item.getInvoice();
        CommonInvoiceStatus invoiceStatus = invoice == null ? null : invoice.getStatus();
        if (invoiceStatus == CommonInvoiceStatus.NEEDS_ATTENTION) {
            markOrderWaitingCommonInvoice(order);
            return true;
        }
        if (invoiceStatus == null || invoiceStatus == CommonInvoiceStatus.PAID || invoiceStatus == CommonInvoiceStatus.UNPAID || invoiceStatus == CommonInvoiceStatus.BAN || invoiceStatus == CommonInvoiceStatus.ARCHIVED || invoiceStatus == CommonInvoiceStatus.DISABLED) {
            return false;
        }
        Long payable = payableKopecksOrMarkAttention(invoice, item.getOrder());
        if (payable == null) {
            markOrderWaitingCommonInvoice(order);
            return true;
        }
        item.setReady(true);
        item.setAmountKopecks(payable);
        invoiceOrderRepository.save(item);
        markOrderWaitingCommonInvoice(order);
        recalculateInvoice(invoice);
        List<CommonInvoiceOrder> currentItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        if (allOrdersReady(currentItems)) {
            // This entry point is called while the publication transaction
            // already owns one Order row. Never lock any sibling Order here:
            // two last publications for the same invoice could otherwise hold
            // A/B and wait for B/A. Finalize after commit in a fresh canonical
            // Orders(id ASC) -> account -> invoice transaction.
            if (deferReadyCommonInvoiceFinalizationUntilAfterCommit(invoice)) {
                return true;
            }
            if (applyCommonInvoicePrepaymentIfReady(invoice, currentItems)) {
                return true;
            }
        }
        if (isInvoiceReady(invoice.getId())) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(invoice);
            markInvoiceOrdersPublished(invoice.getId());
            if (immediateClientMessagesEnabled()) {
                sendInvoiceAfterCommit(invoice.getId(), false);
            } else {
                invoice.setLastError("auto_send_disabled: моментальные клиентские сообщения выключены");
                invoiceRepository.save(invoice);
            }
        }
        return true;
    }

    CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    /**
     * Locks and validates a linked common invoice before a bad-review task can
     * change the payable amount. A frozen, delivered or historical invoice is
     * never silently rewritten; it needs a supplemental invoice or manual
     * reconciliation. The invoice lock is retained by the caller transaction
     * through the later task and amount updates.
     */
    @Transactional
    public CommonPayableChangeDisposition prepareLinkedOrderPayableChange(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return CommonPayableChangeDisposition.NOT_LINKED;
        }
        CommonInvoiceOrder snapshot = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElse(null);
        if (snapshot == null || snapshot.getInvoice() == null || snapshot.getInvoice().getId() == null) {
            return CommonPayableChangeDisposition.NOT_LINKED;
        }
        CommonInvoice invoice = lockedInvoice(snapshot.getInvoice().getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Связанный общий счет изменился. Обновите данные и повторите действие"));
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Заказ больше не входит в ожидаемый общий счет"));
        if (!Objects.equals(invoice.getId(), item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав общего счета изменился. Обновите данные и повторите действие");
        }
        boolean unpaidCycle = !item.isPaid() && item.isUnpaid() && (invoice.getStatus() == CommonInvoiceStatus.UNPAID || (invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION && attentionError(invoice).startsWith(BAD_REVIEW_SUPPLEMENT_REQUIRED)));
        boolean frozenSuccessorCycle = !item.isPaid() && "BAD_REVIEW_SUCCESSOR".equals(invoice.getInvoicePurpose()) && (invoice.getStatus() != CommonInvoiceStatus.COLLECTING || invoice.getSentAt() != null || invoice.getClientReportedAt() != null);
        if (unpaidCycle || frozenSuccessorCycle) {
            // The delivered common cycle is immutable. Completion is allowed,
            // but a standalone order link is forbidden.
            return CommonPayableChangeDisposition.SUPPLEMENT_REQUIRED;
        }
        if (item.isPaid() || invoice.getStatus() != CommonInvoiceStatus.COLLECTING || invoice.getSentAt() != null || invoice.getClientReportedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма общего счета уже зафиксирована или содержит платежные признаки. " + "Нужен дополнительный счет либо ручная сверка");
        }
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        return CommonPayableChangeDisposition.REFRESH_CURRENT_INVOICE;
    }

    /**
     * Creates the immutable predecessor's idempotent live successor.
     */
    @Transactional
    public boolean createBadReviewSupplementSuccessor(Long orderId, Long taskId) {
        if (orderId == null || orderId <= 0 || taskId == null || taskId <= 0) {
            return false;
        }
        CommonInvoiceOrder snapshot = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElse(null);
        if (snapshot == null || snapshot.getInvoice() == null || snapshot.getInvoice().getId() == null) {
            return false;
        }
        CommonInvoice invoice = lockedInvoice(snapshot.getInvoice().getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Связанный общий счет изменился. Обновите данные и повторите действие"));
        // One successor belongs to one frozen predecessor generation. Every
        // task that arrives while that successor is still COLLECTING refreshes
        // the same cycle instead of creating another invoice.
        String idempotencyKey = "BAD_REVIEW_SUCCESSOR:" + invoice.getId();
        if (invoiceRepository.findByCycleIdempotencyKeyForUpdate(idempotencyKey).isPresent()) {
            return true;
        }
        CommonInvoiceOrder triggeringItem = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Заказ больше не входит в ожидаемый общий счет"));
        if (!Objects.equals(invoice.getId(), triggeringItem.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав общего счета изменился. Обновите данные и повторите действие");
        }
        boolean acceptedState = invoice.getStatus() == CommonInvoiceStatus.UNPAID || (invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION && attentionError(invoice).startsWith(BAD_REVIEW_SUPPLEMENT_REQUIRED));
        if (!acceptedState || triggeringItem.isPaid() || !triggeringItem.isUnpaid()) {
            markBadReviewSupplementAttention(invoice, orderId, taskId, "unsafe_source_state");
            return true;
        }
        List<CommonInvoiceOrder> sourceItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> unpaidItems = sourceItems.stream().filter(CommonInvoiceOrder::isActiveMembership).filter(item -> !item.isPaid()).toList();
        if (unpaidItems.isEmpty() || unpaidItems.stream().anyMatch(item -> !item.isUnpaid())) {
            markBadReviewSupplementAttention(invoice, orderId, taskId, "unsafe_membership");
            return true;
        }
        Map<Long, Long> payableByOrder = new LinkedHashMap<>();
        for (CommonInvoiceOrder sourceItem : unpaidItems) {
            Order sourceOrder = sourceItem.getOrder();
            Long sourceOrderId = sourceOrder == null ? null : sourceOrder.getId();
            Long payable = sourceOrderId == null ? null : payableKopecksOrMarkAttention(invoice, sourceOrder);
            if (payable == null || payable <= 0 || hasBadReviewSuccessorPaymentEvidence(sourceItem)) {
                markBadReviewSupplementAttention(invoice, orderId, taskId, "unsafe_amount_or_payment_evidence");
                return true;
            }
            payableByOrder.put(sourceOrderId, payable);
        }
        CommonInvoice successor = createInvoice(invoice.getAccount());
        successor.setSupersedesInvoice(invoice);
        successor.setInvoicePurpose("BAD_REVIEW_SUCCESSOR");
        successor.setCycleIdempotencyKey(idempotencyKey);
        successor.setTitle(limit(invoice.getTitle() + " - дополнительный счет", 180));
        invoiceRepository.save(successor);
        LocalDateTime linkedAt = LocalDateTime.now();
        List<CommonInvoiceOrder> successorItems = new ArrayList<>();
        sourceItems.stream().filter(CommonInvoiceOrder::isActiveMembership).forEach(sourceItem -> sourceItem.setActiveMembership(false));
        for (CommonInvoiceOrder sourceItem : unpaidItems) {
            CommonInvoiceOrder successorItem = new CommonInvoiceOrder();
            successorItem.setInvoice(successor);
            successorItem.setActiveMembership(true);
            successorItem.setOrder(sourceItem.getOrder());
            successorItem.setAmountKopecks(payableByOrder.get(sourceItem.getOrder().getId()));
            successorItem.setOriginalOrderStatusTitle(sourceItem.getOriginalOrderStatusTitle());
            successorItem.setArchiveSourceOrderStatusTitle(sourceItem.getArchiveSourceOrderStatusTitle());
            successorItem.setReady(false);
            successorItem.setPaid(false);
            successorItem.setUnpaid(false);
            successorItem.setInvoiceLinkedAt(linkedAt);
            successorItems.add(successorItem);
        }
        invoiceOrderRepository.saveAll(sourceItems);
        // CommonInvoiceOrder uses IDENTITY. Flush the inactive predecessor
        // before persisting active successor rows, otherwise MySQL can still
        // reject the successor on uk_common_invoice_active_order.
        entityManager.flush();
        invoiceOrderRepository.saveAll(successorItems);
        recalculateInvoice(successor, successorItems);
        promoteBadReviewSuccessorIfReady(successor, successorItems);
        return true;
    }

    void promoteBadReviewSuccessorIfReady(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || !"BAD_REVIEW_SUCCESSOR".equals(invoice.getInvoicePurpose()) || invoice.getStatus() != CommonInvoiceStatus.COLLECTING || items == null || items.isEmpty()) {
            return;
        }
        boolean stable = items.stream().allMatch(item -> {
            Order order = item.getOrder();
            if (order == null || order.getId() == null || hasActiveRecovery(item)) {
                return false;
            }
            BadReviewTaskSummary summary = badReviewTaskService.getSummaryForOrder(order.getId());
            return summary != null && summary.pending() == 0;
        });
        if (!stable) {
            return;
        }
        items.forEach(item -> item.setReady(true));
        invoiceOrderRepository.saveAll(items);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        // A successor is a new financial presentation. Keep it READY for the
        // canonical common-invoice send action; task completion must not send
        // an independently assembled message, bypass normal approval or move
        // the source order out of "Не оплачено" before actual payment.
        if (appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true) && immediateClientMessagesEnabled()) {
            sendInvoiceAfterCommit(invoice.getId(), false);
        }
    }

    void markBadReviewSupplementAttention(CommonInvoice invoice, Long orderId, Long taskId, String reason) {
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID) {
            invoice.setPreviousStatus(CommonInvoiceStatus.UNPAID.name());
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(BAD_REVIEW_SUPPLEMENT_REQUIRED + " source_invoice=" + invoice.getId() + ";order=" + orderId + ";task=" + taskId + ";reason=" + reason, 512));
        invoiceRepository.save(invoice);
    }

    @Transactional
    public CommonInvoiceDetailsResponse detachOrder(Long invoiceId, Long orderId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanChangePositions(invoice);
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (!invoice.getId().equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }
        Order order = item.getOrder();
        if (item.isPaid()) {
            try {
                closeOrderAsPaidWithoutNextOrder(order, true);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ отмечен оплаченным, но не удалось закрыть его отдельно", e);
            }
        } else {
            restoreDetachedOrderStatus(order, item.getOriginalOrderStatusTitle());
        }
        invoiceOrderRepository.delete(item);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        recalculateInvoice(invoice, items);
        publicationBlockerService.reconcileInvoice(invoiceId);
        if (items.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: в общем счете нет заказов");
            invoiceRepository.save(invoice);
            List<CommonInvoicePaymentRef> paymentRefs = paymentRefEvidenceRows(invoice);
            return new CommonInvoiceDetailsResponse(toInvoiceSummary(invoice, List.of()), List.of(), List.of(), List.of(), toPaymentRefEvidence(paymentRefs, Map.of()), paymentEvidenceToken(invoice, paymentRefs));
        }
        if (isInvoiceReady(invoiceId) && invoice.getStatus() == CommonInvoiceStatus.COLLECTING) {
            invoice.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(invoice);
            markInvoiceOrdersPublished(items);
        }
        return invoiceAfterOrderPrelude(invoiceId);
    }

    /**
     * Narrow compatibility path for deleting an untouched order created by the
     * next-order automation after cancellation of its source payment. This is
     * not a hard-delete bypass: it locks and revalidates the order, invoice and
     * membership, and refuses to detach anything carrying payment state.
     */
    @Transactional
    public boolean detachOrderForDeletion(Long orderId) {
        Order order = orderAggregateMutationLockService.lock(orderId);
        boolean autoCreated = nextOrderRequests.lockCreatedOrigin(orderId);
        if (!autoCreated || !"Новый".equals(statusTitle(order)) || order.getCounter() > 0 || order.isComplete()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автосозданный следующий заказ уже изменен и не может быть безопасно удален");
        }
        Optional<CommonInvoiceOrder> snapshot = invoiceOrderRepository.findByOrderIdWithInvoice(orderId);
        if (snapshot.isEmpty()) {
            return false;
        }
        Long invoiceId = snapshot.map(CommonInvoiceOrder::getInvoice).map(CommonInvoice::getId).orElseThrow(() -> invoiceMembershipChanged("у позиции отсутствует общий счет"));
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId).orElseThrow(() -> invoiceMembershipChanged("общий счет исчез во время удаления заказа"));
        CommonInvoiceOrder item = invoiceOrderRepository.findMembershipByOrderIdForRead(orderId).orElseThrow(() -> invoiceMembershipChanged("позиция исчезла во время удаления заказа"));
        Long currentInvoiceId = item.getInvoice() == null ? null : item.getInvoice().getId();
        if (!Objects.equals(invoiceId, currentInvoiceId)) {
            throw invoiceMembershipChanged("позиция перешла в другой общий счет");
        }
        ensureNoOperationInProgress(invoice);
        ensureDeletionDetachHasNoPaymentState(invoice, item);
        int deleted = invoiceOrderRepository.deleteByOrderId(orderId);
        if (deleted != 1) {
            throw invoiceMembershipChanged("позиция не была отвязана");
        }
        List<CommonInvoiceOrder> remainingItems = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (remainingItems.isEmpty()) {
            invoice.setStatus(CommonInvoiceStatus.DISABLED);
            invoice.setAmountKopecks(0);
            invoice.setPaidKopecks(0);
            invoice.setNextReminderAt(null);
            invoice.setLastError("empty: автосозданный заказ удален после отмены оплаты");
            invoiceRepository.save(invoice);
        } else {
            recalculateInvoice(invoice, remainingItems);
            promoteCollectingInvoiceToReadyIfPossible(invoice, remainingItems);
        }
        publicationBlockerService.reconcileInvoice(invoiceId);
        return true;
    }

    void ensureDeletionDetachHasNoPaymentState(CommonInvoice invoice, CommonInvoiceOrder item) {
        boolean itemHasPayment = item.isPaid() || item.isUnpaid() || item.getPaidAt() != null || !normalize(item.getPaymentMethod()).isBlank() || !normalize(item.getManualPaidBy()).isBlank() || !normalize(item.getManualPaymentComment()).isBlank() || !normalize(item.getManualPaymentReceiptUrl()).isBlank();
        boolean invoiceHasInitOrBinding = PAYMENT_INIT_IN_PROGRESS.equals(normalize(invoice.getLastError())) || !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankTerminalKey()).isBlank() || invoice.getTbankPaymentAmountKopecks() != null || invoice.getTbankPaymentCreatedAt() != null || !normalize(invoice.getPaymentUrl()).isBlank();
        boolean invoiceCanChange = invoice.getStatus() == CommonInvoiceStatus.COLLECTING || invoice.getStatus() == CommonInvoiceStatus.READY;
        boolean activeRefs = paymentRefRepository.existsByInvoice_IdAndStatusIn(invoice.getId(), DELETION_DETACH_BLOCKING_PAYMENT_REF_STATUSES);
        if (itemHasPayment || invoiceHasInitOrBinding || !invoiceCanChange || activeRefs || hasFrozenCommonPaymentRoute(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ связан с общим счетом, который уже содержит платежные данные; нужна ручная проверка");
        }
    }

    Optional<CommonBillingAccount> enabledAccountForCompany(Long companyId) {
        return accountCompanyRepository.findEnabledLinksForCompany(companyId).stream().map(CommonBillingAccountCompany::getAccount).findFirst();
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    Optional<CommonInvoice> lockedInvoiceAfterOrderPrelude(Long invoiceId) {
        return settlementService.lockedInvoiceAfterOrderPrelude(invoiceId);
    }

    Map<Long, Order> lockOrderAggregatesWithEntities(Collection<Long> orderIds) {
        return settlementService.lockOrderAggregatesWithEntities(orderIds);
    }

    Map<Long, List<PaymentLink>> lockPaymentLinksForOrders(Collection<Long> orderIds) {
        return invoiceInitialization.lockPaymentLinksForOrders(orderIds);
    }

    int closeProvablyUnstartedStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder, Long invoiceId) {
        return invoiceInitialization.closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksByOrder, invoiceId);
    }

    /**
     * An UNPAID marker is the expected source state for a bad-review successor,
     * not evidence that money moved. Only durable settlement/evidence fields
     * make cloning that position unsafe.
     */
    boolean hasBadReviewSuccessorPaymentEvidence(CommonInvoiceOrder item) {
        return item != null && (item.isPaid() || item.getPaidAt() != null || item.getSourcePaymentLinkId() != null || !normalize(item.getPaymentMethod()).isBlank() || !normalize(item.getManualPaidBy()).isBlank() || !normalize(item.getManualPaymentComment()).isBlank() || !normalize(item.getManualPaymentReceiptUrl()).isBlank());
    }

    boolean isManualPayment(PaymentLink link) {
        return settlementService.isManualPayment(link);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    void sendInvoiceAfterCommit(Long invoiceId, boolean manual) {
        settlementService.sendInvoiceAfterCommit(invoiceId, manual);
    }

    boolean deferReadyCommonInvoiceFinalizationUntilAfterCommit(CommonInvoice invoice) {
        if (invoice == null || invoice.getId() == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        Long invoiceId = invoice.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                try {
                    finalizeReadyPublicationInvoice(invoiceId, null);
                } catch (RuntimeException e) {
                    // Ready flags and any PREPAID ref are durable. A later
                    // refresh retries under the same canonical lock order.
                    log.warn("Не удалось завершить готовый общий счет {} после коммита", invoiceId, e);
                }
            }
        });
        return true;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public boolean finalizePublishedInvoiceForOrder(long orderId) {
        // Scalar discovery precedes a fresh transaction which locks every member
        // Order in ascending order, then account/invoice and revalidates membership.
        var binding = invoiceOrderRepository.findCurrentInvoiceBindingsByOrderIds(List.of(orderId)).stream().findFirst();
        return binding.isEmpty() || finalizeReadyPublicationInvoice(binding.orElseThrow().getInvoiceId(), orderId);
    }

    boolean finalizeReadyPublicationInvoice(Long invoiceId, Long expectedOrderId) {
        return writeTransaction(() -> {
            CommonInvoice locked = lockedInvoice(invoiceId).orElse(null);
            if (locked == null) return true;
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
            if (expectedOrderId != null && items.stream().noneMatch(item -> item.isActiveMembership()
                    && item.getOrder() != null && Objects.equals(expectedOrderId, item.getOrder().getId()))) return false;
            // Replaying an acknowledged phase must never resurrect a terminal,
            // attention or already delivered invoice or re-send an existing READY one.
            if (locked.getStatus() != CommonInvoiceStatus.COLLECTING) return true;
            if (items.stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull)
                    .map(this::statusTitle)
                    .anyMatch(title -> Set.of("Не оплачено", "Бан", "Архив").contains(title == null ? "" : title))) return true;
            if (!allOrdersReady(items)) return false;
            recalculateInvoice(locked, items);
            if (applyCommonInvoicePrepaymentIfReady(locked, items)) return true;
            if (!isInvoiceReady(invoiceId)) return false;
            locked.setStatus(CommonInvoiceStatus.READY);
            invoiceRepository.save(locked);
            markInvoiceOrdersPublished(items);
            if (immediateClientMessagesEnabled()) sendInvoiceAfterCommit(invoiceId, false);
            else {
                locked.setLastError("auto_send_disabled: моментальные клиентские сообщения выключены");
                invoiceRepository.save(locked);
            }
            // READY/PARTIALLY_PAID are already durable sources for the existing
            // unsent-invoice scheduler if its immediate-send callback is lost.
            return true;
        });
    }

    void ensureCompanyNotEnabledInAnotherAccount(Long accountId, Long companyId) {
        accountCompanyRepository.findConfiguredEnabledLinksForCompany(companyId).stream().filter(link -> !link.getAccount().getId().equals(accountId)).findFirst().ifPresent(link -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Компания уже включена в общий счет: " + link.getAccount().getName());
        });
    }

    boolean isEnabledCompanyLink(Long accountId, Long companyId, CommonBillingAccount account) {
        if (account == null || !account.isEnabled() || !Objects.equals(accountId, account.getId())) {
            return false;
        }
        return accountCompanyRepository.findByAccount_IdAndCompany_Id(accountId, companyId).map(CommonBillingAccountCompany::isEnabled).orElse(false);
    }

    List<CommonInvoice> currentInvoiceSnapshots(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        return invoiceRepository.findCurrentForAccount(accountId, ATTACHABLE_INVOICE_STATUSES, PageRequest.of(0, 50));
    }

    Set<Long> invoiceIds(Collection<CommonInvoice> invoices) {
        if (invoices == null || invoices.isEmpty()) {
            return Set.of();
        }
        return invoices.stream().filter(Objects::nonNull).map(CommonInvoice::getId).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
    }

    Map<Long, InvoiceOrderBinding> invoiceBindings(Collection<Long> invoiceIds) {
        return settlementService.invoiceBindings(invoiceIds);
    }

    Map<Long, CommonInvoice> loadInvoiceSnapshots(Collection<Long> invoiceIds) {
        return settlementService.loadInvoiceSnapshots(invoiceIds);
    }

    Map<Long, CommonBillingAccount> lockAccountsInCanonicalOrder(Map<Long, CommonBillingAccount> snapshots) {
        return settlementService.lockAccountsInCanonicalOrder(snapshots);
    }

    Map<Long, CommonInvoice> lockInvoicesInCanonicalOrder(Map<Long, CommonInvoice> snapshots) {
        return settlementService.lockInvoicesInCanonicalOrder(snapshots);
    }

    ResponseStatusException invoiceMembershipChanged(String detail) {
        return settlementService.invoiceMembershipChanged(detail);
    }

    CommonInvoiceOrder attachOrderToCurrentInvoice(CommonBillingAccount accountSnapshot, Long orderId, Long expectedCompanyId) {
        if (accountSnapshot == null || accountSnapshot.getId() == null || orderId == null || expectedCompanyId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не выбраны заказ, компания или общий плательщик");
        }
        Long accountId = accountSnapshot.getId();
        List<CommonInvoice> invoiceSnapshots = currentInvoiceSnapshots(accountId);
        Set<Long> expectedInvoiceIds = invoiceIds(invoiceSnapshots);
        Map<Long, InvoiceOrderBinding> expectedBindings = invoiceBindings(expectedInvoiceIds);
        Set<Long> orderIdsToLock = new TreeSet<>(expectedBindings.keySet());
        orderIdsToLock.add(orderId);
        Map<Long, Order> lockedOrders = lockOrderAggregatesWithEntities(orderIdsToLock);
        Map<Long, List<PaymentLink>> lockedPaymentLinks = lockPaymentLinksForOrders(Set.of(orderId));
        closeProvablyUnstartedStandaloneRoutesOrThrow(lockedPaymentLinks, null);
        Map<Long, CommonBillingAccount> lockedAccounts = lockAccountsInCanonicalOrder(Map.of(accountId, accountSnapshot));
        Map<Long, CommonInvoice> lockedInvoices = lockInvoicesInCanonicalOrder(loadInvoiceSnapshots(expectedInvoiceIds));
        Order lockedOrder = lockedOrders.get(orderId);
        if (lockedOrder == null || lockedOrder.getCompany() == null || !Objects.equals(expectedCompanyId, lockedOrder.getCompany().getId())) {
            throw invoiceMembershipChanged("компания заказа изменилась");
        }
        if (invoiceOrderRepository.findMembershipByOrderIdForRead(orderId).isPresent()) {
            throw invoiceMembershipChanged("заказ уже привязан к общему счету");
        }
        CommonBillingAccount lockedAccount = lockedAccounts.get(accountId);
        if (!isEnabledCompanyLink(accountId, expectedCompanyId, lockedAccount)) {
            throw invoiceMembershipChanged("связь компании с общим плательщиком изменилась");
        }
        ensureCompanyNotEnabledInAnotherAccount(accountId, expectedCompanyId);
        Set<Long> currentInvoiceIds = invoiceIds(currentInvoiceSnapshots(accountId));
        if (!currentInvoiceIds.equals(expectedInvoiceIds) || !invoiceBindings(currentInvoiceIds).equals(expectedBindings)) {
            throw invoiceMembershipChanged("топология открытого общего счета изменилась");
        }
        List<CommonInvoice> currentInvoices = currentInvoiceIds.stream().sorted().map(lockedInvoices::get).filter(Objects::nonNull).toList();
        CommonInvoice invoice = currentInvoices.isEmpty() ? createInvoice(lockedAccount) : normalizeAttachableInvoices(lockedAccount, currentInvoices);
        CommonInvoiceOrder item = attachOrderWithoutInvoiceRefresh(invoice, lockedOrder);
        consumeVerifiedManualRouteAfterAttach(invoice, lockedPaymentLinks);
        recalculateInvoice(invoice);
        publicationBlockerService.reconcileInvoice(invoice.getId());
        return item;
    }

    CommonInvoiceOrder attachOrderWithoutInvoiceRefresh(CommonInvoice invoice, Order order) {
        ensureCommonPaymentRouteAllowsCompositionChange(invoice);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setInvoice(invoice);
        item.setOrder(order);
        Long payable = payableKopecksOrMarkAttention(invoice, order);
        item.setAmountKopecks(payable == null ? 0 : payable);
        item.setOriginalOrderStatusTitle(limit(statusTitle(order), 64));
        return invoiceOrderRepository.save(item);
    }

    void consumeVerifiedManualRouteAfterAttach(CommonInvoice invoice, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        if (invoice == null || invoice.getId() == null || paymentLinksByOrder == null || paymentLinksByOrder.isEmpty()) {
            return;
        }
        paymentLinksByOrder.values().stream().flatMap(Collection::stream).filter(Objects::nonNull).filter(link -> link.getStatus() == PaymentLinkStatus.CANCELED).filter(this::isManualPayment).filter(link -> normalize(link.getLastError()).startsWith(MANUAL_PAYMENT_ABSENT_VERIFIED_PREFIX)).forEach(link -> {
            link.setLastError(limit(COMMON_INVOICE_ROUTE_ATTACHED_PREFIX + "invoice=" + invoice.getId() + "; " + normalize(link.getLastError()), 512));
            paymentLinkRepository.save(link);
        });
    }

    CommonInvoice normalizeAttachableInvoices(CommonBillingAccount account, List<CommonInvoice> invoices) {
        if (invoices == null || invoices.isEmpty()) {
            return createInvoice(account);
        }
        invoices.forEach(this::ensureCommonPaymentRouteAllowsCompositionChange);
        CommonInvoice target = invoices.stream().min(Comparator.comparing(invoice -> invoice.getId() == null ? Long.MAX_VALUE : invoice.getId())).orElseGet(() -> createInvoice(account));
        List<CommonInvoice> duplicates = invoices.stream().filter(invoice -> invoice.getId() != null && !invoice.getId().equals(target.getId())).toList();
        if (duplicates.isEmpty()) {
            return target;
        }
        List<Long> duplicateIds = duplicates.stream().map(CommonInvoice::getId).toList();
        List<CommonInvoiceOrder> movedItems = invoiceOrderRepository.findByInvoiceIdsWithOrders(duplicateIds);
        LocalDateTime movedAt = LocalDateTime.now();
        for (CommonInvoiceOrder item : movedItems) {
            item.setInvoice(target);
            item.setInvoiceLinkedAt(movedAt);
            item.setPublicationBlockerSince(null);
        }
        if (!movedItems.isEmpty()) {
            invoiceOrderRepository.saveAll(movedItems);
        }
        for (CommonInvoice duplicate : duplicates) {
            duplicate.setStatus(CommonInvoiceStatus.DISABLED);
            duplicate.setNextReminderAt(null);
            duplicate.setLastError("merged_into: common_invoice_" + target.getId());
            invoiceRepository.save(duplicate);
        }
        List<CommonInvoiceOrder> targetItems = invoiceOrderRepository.findByInvoiceIdWithOrders(target.getId());
        recalculateInvoice(target, targetItems);
        promoteCollectingInvoiceToReadyIfPossible(target, targetItems);
        publicationBlockerService.reconcileInvoice(target.getId());
        log.warn("Объединены дубли открытых общих счетов accountId={}, targetInvoice={}, duplicates={}", account == null ? null : account.getId(), target.getId(), duplicateIds);
        return target;
    }

    CommonInvoice createInvoice(CommonBillingAccount account) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setAccount(account);
        invoice.setInvoicePaymentMode(account == null || account.getInvoicePaymentMode() == null ? InvoicePaymentMode.AUTO_ROUTING : account.getInvoicePaymentMode());
        invoice.setToken(randomToken());
        invoice.setTitle(account.getName() + " - общий счет");
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        return invoiceRepository.save(invoice);
    }

    boolean isInvoiceReady(Long invoiceId) {
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        return areInvoiceItemsReady(items);
    }

    void promoteCollectingInvoiceToReadyIfPossible(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        manualPaymentWorkflow.promoteCollectingInvoiceToReadyIfPossible(invoice, items);
    }

    boolean areInvoiceItemsReady(List<CommonInvoiceOrder> items) {
        return settlementService.areInvoiceItemsReady(items);
    }

    void ensureCommonInvoiceNotNeedsAttention(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceNotNeedsAttention(invoice);
    }

    void ensureNoOperationInProgress(CommonInvoice invoice) {
        invoiceInitialization.ensureNoOperationInProgress(invoice);
    }

    void ensureCommonInvoiceCanChangePositions(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID || invoice.getStatus() == CommonInvoiceStatus.UNPAID || invoice.getStatus() == CommonInvoiceStatus.BAN || invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Закрытый общий счет нельзя менять");
        }
    }

    String attentionError(CommonInvoice invoice) {
        return settlementService.attentionError(invoice);
    }

    boolean applyCommonInvoicePrepaymentIfReady(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return settlementService.applyCommonInvoicePrepaymentIfReady(invoice, items);
    }

    void closeOrderAsPaidWithoutNextOrder(Order order) throws Exception {
        settlementService.closeOrderAsPaidWithoutNextOrder(order);
    }

    void closeOrderAsPaidWithoutNextOrder(Order order, boolean standalonePaymentAlreadyConfirmed) throws Exception {
        settlementService.closeOrderAsPaidWithoutNextOrder(order, standalonePaymentAlreadyConfirmed);
    }

    String orderFailureLabel(CommonInvoiceOrder item) {
        return settlementService.orderFailureLabel(item);
    }

    String orderFailureLabel(Order order) {
        return settlementService.orderFailureLabel(order);
    }

    void recalculateInvoice(CommonInvoice invoice) {
        settlementService.recalculateInvoice(invoice);
    }

    void recalculateInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.recalculateInvoice(invoice, items);
    }

    Long payableKopecksOrMarkAttention(CommonInvoice invoice, Order order) {
        try {
            return amountKopecks(payableSum(order));
        } catch (AmountCalculationException e) {
            log.warn("Не удалось посчитать сумму общего счета {} для заказа {}", invoice == null ? null : invoice.getId(), order == null ? null : order.getId(), e);
            markAmountCalculationFailed(invoice, List.of(orderFailureLabel(order)));
            return null;
        }
    }

    void markAmountCalculationFailed(CommonInvoice invoice, List<String> amountFailures) {
        settlementService.markAmountCalculationFailed(invoice, amountFailures);
    }

    List<CommonInvoicePaymentRef> paymentRefEvidenceRows(CommonInvoice invoice) {
        return invoiceDetailsAssembler.paymentRefEvidenceRows(invoice);
    }

    List<CommonInvoicePaymentRefResponse> toPaymentRefEvidence(List<CommonInvoicePaymentRef> paymentRefs, Map<String, String> terminalLabels) {
        return invoiceDetailsAssembler.toPaymentRefEvidence(paymentRefs, terminalLabels);
    }

    String paymentEvidenceToken(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        return invoiceDetailsAssembler.paymentEvidenceToken(invoice, paymentRefs);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoicePresenter.toInvoiceSummary(invoice, items);
    }

    CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items, String tbankTerminalLabel) {
        return invoicePresenter.toInvoiceSummary(invoice, items, tbankTerminalLabel);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    boolean allOrdersReady(List<CommonInvoiceOrder> items) {
        return settlementService.allOrdersReady(items);
    }

    boolean hasActiveRecovery(List<CommonInvoiceOrder> items) {
        return settlementService.hasActiveRecovery(items);
    }

    boolean hasActiveRecovery(CommonInvoiceOrder item) {
        return settlementService.hasActiveRecovery(item);
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return manualPaymentWorkflow.hasFrozenCommonPaymentRoute(invoice);
    }

    void ensureCommonPaymentRouteAllowsCompositionChange(CommonInvoice invoice) {
        if (hasFrozenCommonPaymentRoute(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав общего счета уже зафиксирован платежным маршрутом. Создайте следующий общий счет.");
        }
    }

    boolean immediateClientMessagesEnabled() {
        return settlementService.immediateClientMessagesEnabled();
    }

    void restoreDetachedOrderStatus(Order order, String originalStatus) {
        if (order == null || order.getId() == null) {
            return;
        }
        String targetStatus = normalize(originalStatus);
        if (targetStatus.isBlank() || STATUS_WAITING_COMMON_INVOICE.equals(targetStatus)) {
            targetStatus = STATUS_PUBLIC;
        }
        order.setStatus(orderStatusService.getOrderStatusByTitle(targetStatus));
        orderRepository.save(order);
    }

    void markOrderWaitingCommonInvoice(Order order) {
        if (order == null || STATUS_WAITING_COMMON_INVOICE.equals(statusTitle(order))) {
            return;
        }
        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_WAITING_COMMON_INVOICE));
        orderRepository.save(order);
    }

    void markInvoiceOrdersPublished(Long invoiceId) {
        settlementService.markInvoiceOrdersPublished(invoiceId);
    }

    void markInvoiceOrdersPublished(List<CommonInvoiceOrder> items) {
        settlementService.markInvoiceOrdersPublished(items);
    }

    BigDecimal payableSum(Order order) {
        return settlementService.payableSum(order);
    }

    long amountKopecks(BigDecimal amount) {
        return settlementService.amountKopecks(amount);
    }

    String statusTitle(Order order) {
        return settlementService.statusTitle(order);
    }

    String randomToken() {
        return CommonInvoicePaymentIdentity.randomToken();
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }
}
