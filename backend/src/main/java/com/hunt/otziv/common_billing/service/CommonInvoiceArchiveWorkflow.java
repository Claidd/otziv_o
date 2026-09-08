package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceBoardWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonBillingAccountWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonBillingCompanyReconciliationWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceMembershipWorkflow.*;
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
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchivePreviewResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchivePreviewResponse.CommonInvoiceArchiveOrderPreview;
import com.hunt.otziv.common_billing.dto.CommonInvoiceCloseRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.status.policy.OrderManualArchivePolicy;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Owns archive/ban/unpaid transitions, their recovery checks and the original atomic order changes. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceArchiveWorkflow {

    private final CommonInvoiceBoardWorkflow invoiceBoardWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceSettlementService settlementService;

    static final String STATUS_TO_CHECK = "В проверку";

    static final Set<CommonInvoiceStatus> MARK_UNPAID_STATUSES = Set.of(CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID);

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final NextOrderRequestRepository nextOrderRequestRepository;

    @Autowired
    @Lazy
    private OrderStatusTransitionService orderStatusTransitionService;

    private final BadReviewTaskService badReviewTaskService;

    private final ManagerPermissionService managerPermissionService;

    private final ContractorPaymentShadowService contractorPaymentShadowService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final ReviewRecoveryGateService recoveryGateService;

    CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markUnpaid(Long invoiceId) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        ensureCommonInvoiceCanBeMarkedUnpaid(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (items.stream().noneMatch(item -> !item.isPaid())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В общем счете нет неоплаченных заказов");
        }
        List<String> failures = new ArrayList<>();
        List<CommonInvoiceOrder> changedItems = new ArrayList<>();
        for (CommonInvoiceOrder item : items) {
            if (item.isPaid()) {
                continue;
            }
            try {
                Order order = item.getOrder();
                if (order == null || order.getId() == null) {
                    throw new IllegalStateException("Заказ не найден");
                }
                orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), STATUS_NOT_PAID);
                changedItems.add(item);
            } catch (Exception e) {
                failures.add(orderFailureLabel(item));
                log.warn("Не удалось перевести заказ {} из общего счета {} в Не оплачено", item.getOrder() == null ? null : item.getOrder().getId(), invoiceId, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не все заказы общего счета удалось перевести в Не оплачено: " + String.join(", ", failures));
        }
        ensureBadReviewTasksForItems(changedItems);
        taskReceiptIntegrationService.release(invoice, "Общий счет переведен в статус Не оплачено");
        changedItems.forEach(item -> item.setUnpaid(true));
        archiveAndClearCurrentPaymentRef(invoice, "manual_unpaid");
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceOrderRepository.saveAll(items);
        invoiceRepository.save(invoice);
        scheduleContractorShadowRelease(invoiceId);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(readOnly = true)
    public CommonInvoiceArchivePreviewResponse archivePreview(Long invoiceId) {
        CommonInvoice invoice = invoiceRepository.findByIdWithAccount(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        return buildArchivePreview(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId));
    }

    @Transactional
    public CommonInvoiceDetailsResponse archiveInvoice(Long invoiceId, CommonInvoiceCloseRequest request, Principal principal) {
        if (request == null || !request.confirm()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Архивирование требует confirm=true");
        }
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        CommonInvoiceArchivePreviewResponse preview = buildArchivePreview(invoice, items);
        if (!preview.allowed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет нельзя архивировать: " + String.join("; ", preview.blockers()));
        }
        for (CommonInvoiceOrder item : items) {
            Order order = item.getOrder();
            item.setArchiveSourceOrderStatusTitle(OrderManualArchivePolicy.statusTitle(order));
            try {
                orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), STATUS_ARCHIVE);
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось архивировать заказ #" + order.getId() + ": " + exception.getMessage(), exception);
            }
        }
        invoiceOrderRepository.saveAll(items);
        archiveAndClearCurrentPaymentRef(invoice, "manual_archive");
        closeInvoice(invoice, CommonInvoiceStatus.ARCHIVED, "MANUAL_ARCHIVE", principal);
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse restoreLiveArchivedInvoice(Long invoiceId, Principal principal) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (invoice.getStatus() != CommonInvoiceStatus.ARCHIVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Из live можно восстановить только общий счет, закрытый вручную");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        for (CommonInvoiceOrder item : items) {
            Order order = item.getOrder();
            String targetStatus = normalize(item.getArchiveSourceOrderStatusTitle());
            if (!OrderManualArchivePolicy.ALLOWED_SOURCE_STATUSES.contains(targetStatus)) {
                targetStatus = STATUS_TO_CHECK;
            }
            try {
                orderStatusTransitionService.changeStatusForPrivilegedCommonBillingOrder(order.getId(), targetStatus);
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось восстановить заказ #" + order.getId() + ": " + exception.getMessage(), exception);
            }
        }
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        invoice.setToken(randomToken());
        invoice.setPreviousStatus(null);
        invoice.setClosedAt(null);
        invoice.setClosedBy(null);
        invoice.setCloseReason(null);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markBan(Long invoiceId, Principal principal) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        if (invoice.getStatus() != CommonInvoiceStatus.UNPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В Бан можно перевести только общий счет в статусе Не оплачено");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        List<CommonInvoiceOrder> unpaidItems = items.stream().filter(item -> !item.isPaid()).toList();
        if (unpaidItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В общем счете нет неоплаченных заказов для Бана");
        }
        ensureBadReviewTasksForItems(unpaidItems);
        BadReviewTaskSummary summary = aggregateBadReviewSummary(unpaidItems);
        boolean privileged = currentUserCanForceBan();
        if (!privileged && summary.total() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Бан менеджеру доступен после создания плохих задач");
        }
        if (!privileged && summary.pending() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала выполните все плохие задачи общего счета");
        }
        List<String> failures = new ArrayList<>();
        for (CommonInvoiceOrder item : unpaidItems) {
            try {
                Order order = item.getOrder();
                if (order == null || order.getId() == null) {
                    throw new IllegalStateException("Заказ не найден");
                }
                if (privileged) {
                    badReviewTaskService.cancelPendingTasksForOrder(order);
                    orderStatusTransitionService.changeStatusForPrivilegedCommonBillingOrder(order.getId(), STATUS_BAN);
                } else {
                    orderStatusTransitionService.changeStatusForCommonBillingOrder(order.getId(), STATUS_BAN);
                }
            } catch (Exception e) {
                failures.add(orderFailureLabel(item));
                log.warn("Не удалось перевести заказ {} из общего счета {} в Бан", item.getOrder() == null ? null : item.getOrder().getId(), invoiceId, e);
            }
        }
        if (!failures.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не все заказы общего счета удалось перевести в Бан: " + String.join(", ", failures));
        }
        archiveAndClearCurrentPaymentRef(invoice, "manual_ban");
        closeInvoice(invoice, CommonInvoiceStatus.BAN, "BAN", principal);
        invoiceRepository.save(invoice);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    CommonInvoiceDetailsResponse markBan(Long invoiceId) {
        return markBan(invoiceId, () -> "system");
    }

    CommonInvoiceArchivePreviewResponse buildArchivePreview(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        List<String> invoiceBlockers = new ArrayList<>();
        if (invoice.getStatus() != CommonInvoiceStatus.COLLECTING) {
            invoiceBlockers.add("счет должен находиться в статусе сбора");
        }
        if (invoice.getPaidKopecks() > 0 || items.stream().anyMatch(CommonInvoiceOrder::isPaid)) {
            invoiceBlockers.add("по счету уже есть оплата");
        }
        if (invoice.getSentAt() != null || !normalize(invoice.getPaymentUrl()).isBlank() || !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank()) {
            invoiceBlockers.add("по счету уже начат платежный процесс");
        }
        if (hasFrozenCommonPaymentRoute(invoice)) {
            invoiceBlockers.add("платёжный маршрут уже выдан клиенту");
        }
        if (items.isEmpty()) {
            invoiceBlockers.add("в общем счете нет заказов");
        }
        List<CommonInvoiceArchiveOrderPreview> orderPreviews = items.stream().map(item -> {
            Order order = item.getOrder();
            List<String> blockers = archiveOrderBlockers(order);
            return new CommonInvoiceArchiveOrderPreview(order == null ? null : order.getId(), order == null || order.getCompany() == null ? "" : order.getCompany().getTitle(), OrderManualArchivePolicy.statusTitle(order), blockers.isEmpty(), blockers);
        }).toList();
        orderPreviews.stream().filter(order -> !order.allowed()).forEach(order -> invoiceBlockers.add("заказ #" + order.orderId() + ": " + String.join(", ", order.blockers())));
        return new CommonInvoiceArchivePreviewResponse(invoice.getId(), invoiceBlockers.isEmpty(), items.size(), orderPreviews, List.copyOf(invoiceBlockers));
    }

    List<String> archiveOrderBlockers(Order order) {
        List<String> blockers = new ArrayList<>();
        if (order == null || order.getId() == null) {
            blockers.add("заказ не найден");
            return blockers;
        }
        if (!OrderManualArchivePolicy.isAllowed(order)) {
            blockers.add("статус \"" + OrderManualArchivePolicy.statusTitle(order) + "\" не разрешен");
        }
        if (recoveryGateService.hasActiveRecoveryTasks(order.getId())) {
            blockers.add("есть активная задача восстановления");
        }
        BadReviewTaskSummary badReviewSummary = badReviewTaskService.getSummaryForOrder(order.getId());
        if (badReviewSummary != null && badReviewSummary.pending() > 0) {
            blockers.add("есть активная плохая задача");
        }
        nextOrderRequestRepository.findBySourceOrderId(order.getId()).filter(next -> next.getStatus() == NextOrderRequestStatus.PENDING || next.getStatus() == NextOrderRequestStatus.FAILED).ifPresent(next -> blockers.add("есть незавершенный запрос следующего заказа"));
        return blockers;
    }

    void closeInvoice(CommonInvoice invoice, CommonInvoiceStatus targetStatus, String closeReason, Principal principal) {
        invoice.setPreviousStatus(invoice.getStatus() == null ? null : invoice.getStatus().name());
        invoice.setStatus(targetStatus);
        invoice.setClosedAt(LocalDateTime.now());
        String actor = principal == null ? "system" : normalize(principal.getName());
        invoice.setClosedBy(limit(actor.isBlank() ? "system" : actor, 160));
        invoice.setCloseReason(closeReason);
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
    }

    void ensureBadReviewTasksForItems(List<CommonInvoiceOrder> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (CommonInvoiceOrder item : items) {
            Order order = item == null ? null : item.getOrder();
            if (order == null || order.getId() == null) {
                continue;
            }
            try {
                badReviewTaskService.createTasksForUnpaidOrder(order);
            } catch (RuntimeException e) {
                log.warn("Не удалось создать плохие задачи для заказа {} из общего счета", order.getId(), e);
            }
        }
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
        return invoiceDelivery.lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
    }

    void ensureCommonInvoiceNotNeedsAttention(CommonInvoice invoice) {
        invoiceDelivery.ensureCommonInvoiceNotNeedsAttention(invoice);
    }

    void ensureCommonInvoiceCanBeMarkedUnpaid(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (!MARK_UNPAID_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В Не оплачено можно перевести только уже выставленный общий счет");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплаченный общий счет нельзя перевести в Не оплачено");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.UNPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже находится в статусе Не оплачено");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.BAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже находится в статусе Бан");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отключенный общий счет нельзя перевести в Не оплачено");
        }
    }

    String orderFailureLabel(CommonInvoiceOrder item) {
        return settlementService.orderFailureLabel(item);
    }

    String orderFailureLabel(Order order) {
        return settlementService.orderFailureLabel(order);
    }

    BadReviewTaskSummary aggregateBadReviewSummary(List<CommonInvoiceOrder> items) {
        return invoiceBoardWorkflow.aggregateBadReviewSummary(items);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        invoiceDelivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    boolean currentUserCanForceBan() {
        Authentication authentication = currentAuthentication();
        return managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER");
    }

    Authentication currentAuthentication() {
        return invoiceDelivery.currentAuthentication();
    }

    void scheduleContractorShadowRelease(Long invoiceId) {
        if (invoiceId == null) {
            return;
        }
        Runnable release = () -> {
            try {
                contractorPaymentShadowService.releaseForUnpaidCommonInvoice(invoiceId, "Общий счет переведен в статус \"Не оплачено\"");
            } catch (RuntimeException e) {
                log.error("Не удалось освободить тестовый резерв общего счета {}", invoiceId, e);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    release.run();
                }
            });
        } else {
            release.run();
        }
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return manualPaymentWorkflow.hasFrozenCommonPaymentRoute(invoice);
    }

    String randomToken() {
        return CommonInvoicePaymentIdentity.randomToken();
    }

    void archiveAndClearCurrentPaymentRef(CommonInvoice invoice, String reason) {
        settlementService.archiveAndClearCurrentPaymentRef(invoice, reason);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }
}
