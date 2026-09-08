package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoicePositionPaymentWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeletionWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCheckoutWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceRecoveryWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceReviewApprovalWorkflow.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceArchiveWorkflow.*;
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
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountRequest;
import com.hunt.otziv.common_billing.dto.CommonBillingAccountResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOptionsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchivePreviewResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceCloseRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceManualCardPaymentRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRouteChangeContextResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentRouteChangeRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoicePaymentInitCheckRequest;
import com.hunt.otziv.common_billing.dto.CommonInvoiceSummaryResponse;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.dto.InvoicePaymentModeChangeRequest;
import com.hunt.otziv.common_billing.dto.PublicCommonInvoiceResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaAcquiringInternetPaymentWebhook;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.CreatePaymentResponse;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonBillingService {

    private final CommonInvoicePositionPaymentWorkflow invoicePositionPaymentWorkflow;

    private final CommonInvoiceDeletionWorkflow invoiceDeletionWorkflow;

    private final CommonInvoiceCheckoutWorkflow invoiceCheckoutWorkflow;

    private final CommonInvoiceRecoveryWorkflow invoiceRecoveryWorkflow;

    private final CommonInvoiceReviewApprovalWorkflow invoiceReviewApprovalWorkflow;

    private final CommonInvoiceArchiveWorkflow invoiceArchiveWorkflow;

    private final CommonInvoiceBoardWorkflow invoiceBoardWorkflow;

    private final CommonBillingAccountWorkflow accountWorkflow;

    private final CommonBillingCompanyReconciliationWorkflow companyReconciliation;

    private final CommonInvoiceMembershipWorkflow invoiceMembershipWorkflow;

    private final CommonInvoicePaymentRouteWorkflow invoiceRouteWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceTochkaReconciliationService invoiceReconciliation;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceDetailsAssembler invoiceDetailsAssembler;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoiceCancellationService invoiceCancellation;

    private final CommonInvoicePresenter invoicePresenter;

    public static final String STATUS_WAITING_COMMON_INVOICE = CommonInvoiceSettlementService.STATUS_WAITING_COMMON_INVOICE;

    private final CommonInvoiceSettlementService settlementService;

    private static final String STATUS_TO_PUBLISH = "Публикация";

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    @Transactional(readOnly = true)
    public boolean hasClientReportedPaymentForOrder(Long orderId) {
        return orderId != null && invoiceRepository.countClientReportedPaymentsByOrderId(orderId) > 0;
    }

    public List<CommonBillingAccountResponse> accounts() {
        return accountWorkflow.accounts();
    }

    public List<CommonBillingAccountResponse> accountsForCompany(Long companyId) {
        return accountWorkflow.accountsForCompany(companyId);
    }

    public List<OrderDTOList> managerBoardCards(String boardStatus, String keyword, Long companyId, Set<Long> visibleManagerIds, String sortDirection) {
        return invoiceBoardWorkflow.managerBoardCards(boardStatus, keyword, companyId, visibleManagerIds, sortDirection);
    }

    public Set<Long> linkedBoardOrderIds(Collection<Long> orderIds) {
        return invoiceBoardWorkflow.linkedBoardOrderIds(orderIds);
    }

    public int countLinkedBoardOrdersMatching(String orderStatus, String keyword, Long companyId, Set<Long> visibleManagerIds) {
        return invoiceBoardWorkflow.countLinkedBoardOrdersMatching(orderStatus, keyword, companyId, visibleManagerIds);
    }

    public Map<String, Integer> countManagerBoardCards(Set<Long> visibleManagerIds) {
        return invoiceBoardWorkflow.countManagerBoardCards(visibleManagerIds);
    }

    public Map<String, Integer> countLinkedManagerBoardOrders(Set<Long> visibleManagerIds) {
        return invoiceBoardWorkflow.countLinkedManagerBoardOrders(visibleManagerIds);
    }

    /**
     * Loads only the requested board page after SQL filtering and counting.
     */
    public ManagerBoardPage managerBoardPage(String boardStatus, String keyword, Long companyId, Set<Long> visibleManagerIds, String sortDirection, int pageNumber, int pageSize) {
        var result = invoiceBoardWorkflow.managerBoardPage(boardStatus, keyword, companyId, visibleManagerIds, sortDirection, pageNumber, pageSize);
        return new ManagerBoardPage(result.cards(), result.totalCards(), result.linkedOrderCount());
    }

    /**
     * Aggregates both common cards and linked orders in SQL.
     */
    public ManagerBoardMetrics managerBoardMetrics(Set<Long> visibleManagerIds) {
        var result = invoiceBoardWorkflow.managerBoardMetrics(visibleManagerIds);
        return new ManagerBoardMetrics(result.cardCounts(), result.linkedOrderCounts());
    }

    public CommonBillingAccountResponse createAccount(CommonBillingAccountRequest request) {
        return accountWorkflow.createAccount(request);
    }

    public CommonBillingAccountResponse account(Long accountId) {
        return accountWorkflow.account(accountId);
    }

    public CommonBillingAccountResponse updateAccount(Long accountId, CommonBillingAccountRequest request) {
        return accountWorkflow.updateAccount(accountId, request);
    }

    public CommonBillingAccountResponse addCompany(Long accountId, Long companyId) {
        return accountWorkflow.addCompany(accountId, companyId);
    }

    public CommonBillingAccountResponse removeCompany(Long accountId, Long companyId, boolean detachCurrent) {
        return accountWorkflow.removeCompany(accountId, companyId, detachCurrent);
    }

    public boolean attachOrderIfNeeded(Order order) {
        return invoiceMembershipWorkflow.attachOrderIfNeeded(order);
    }

    public boolean isOrderInActiveCommonInvoice(Long orderId) {
        return settlementService.isOrderInActiveCommonInvoice(orderId);
    }

    @Transactional(readOnly = true)
    public Set<Long> findOrderIdsInActiveCommonInvoices(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Set.of();
        }
        List<Long> normalized = orderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<Long> linked = new HashSet<>();
        for (int offset = 0; offset < normalized.size(); offset += BULK_QUERY_CHUNK_SIZE) {
            linked.addAll(invoiceOrderRepository.findLinkedOrderIds(normalized.subList(offset, Math.min(normalized.size(), offset + BULK_QUERY_CHUNK_SIZE)), BOARD_INVOICE_STATUSES));
        }
        return linked;
    }

    public boolean refreshLinkedOrderAmount(Long orderId) {
        return invoiceMembershipWorkflow.refreshLinkedOrderAmount(orderId);
    }

    public boolean completePublishedOrderIntoCommonInvoice(Order order) {
        return invoiceMembershipWorkflow.completePublishedOrderIntoCommonInvoice(order);
    }

    public CommonInvoiceDetailsResponse sendInvoice(Long invoiceId, boolean manual) {
        return invoiceDelivery.sendInvoice(invoiceId, manual);
    }

    private void sendInvoiceMessage(Long invoiceId, boolean manual, boolean paymentRouteChanged, boolean checkVisibility) {
        invoiceDelivery.sendInvoiceMessage(invoiceId, manual, paymentRouteChanged, checkVisibility);
    }

    /**
     * System-only send path used by durable post-commit automation.
     */
    void sendInvoiceAutomatically(Long invoiceId, boolean manual) {
        sendInvoiceMessage(invoiceId, manual, false, false);
    }

    public int sendDueReminders(int limit) {
        return invoiceDelivery.sendDueReminders(limit);
    }

    public int sendUnsentActionInvoices(int limit) {
        return invoiceDelivery.sendUnsentActionInvoices(limit);
    }

    public int cancelPendingArchivedPayments(int limit) {
        return invoiceCancellation.cancelPendingArchivedPayments(limit);
    }

    /**
     * Reconciles Tochka attempts without ever inventing a local cancel. A CREATED payment remains
     * blocking until GET reports EXPIRED. APPROVED cancellation attempts are refunded once and
     * then observed through GET; an ambiguous Refund result is quarantined instead of retried.
     */
    public int reconcileTochkaPaymentRefs(int limit) {
        return invoiceReconciliation.reconcileTochkaPaymentRefs(limit);
    }

    public CommonInvoiceDetailsResponse sendManualReminder(Long invoiceId) {
        PreparedCommonInvoiceMessage prepared = writeTransaction(() -> preparePaymentMessage(invoiceId, true, true, false, null, true));
        if (prepared != null) {
            ClientMessageSendResult result = sendPreparedPaymentMessage(prepared);
            writeTransaction(() -> {
                finishPaymentMessageSend(prepared, result);
                return null;
            });
        }
        return writeTransaction(() -> invoice(invoiceId));
    }

    public CommonInvoiceDetailsResponse invoice(Long invoiceId) {
        return invoiceDelivery.invoice(invoiceId);
    }

    @Transactional(readOnly = true)
    public boolean hasFrozenManualTaskRoute(Long invoiceId) {
        CommonInvoice invoice = invoiceRepository.findByIdWithAccount(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        return isFrozenManualTaskSource(invoice);
    }

    public PublicCommonInvoiceResponse publicInvoice(String token) {
        return invoiceCheckoutWorkflow.publicInvoice(token);
    }

    /**
     * Token-only, idempotent public evidence; no amount or recipient is accepted from the client.
     */
    public PublicCommonInvoiceResponse reportPublicCommonPayment(String token) {
        return invoiceCheckoutWorkflow.reportPublicCommonPayment(token);
    }

    public void deleteInvoiceWithOrders(Long invoiceId, Principal principal) {
        invoiceDeletionWorkflow.deleteInvoiceWithOrders(invoiceId, principal);
    }

    public CommonInvoiceDetailsResponse markOrderPaid(Long invoiceId, Long orderId, ManualPaymentConfirmationRequest request, Principal principal) {
        return invoicePositionPaymentWorkflow.markOrderPaid(invoiceId, orderId, request, principal);
    }

    CommonInvoiceDetailsResponse markOrderPaid(Long invoiceId, Long orderId) {
        return invoicePositionPaymentWorkflow.markOrderPaid(invoiceId, orderId);
    }

    public boolean applyConfirmedOrderPayment(Long orderId, LocalDateTime paidAt, String reason) {
        return settlementService.applyConfirmedOrderPayment(orderId, paidAt, reason);
    }

    /**
     * Quarantines an invoice when the provider later reverses/refunds the exact
     * standalone payment that funded one of its positions. Paid flags are kept
     * intact until a human reconciles the returned money.
     */
    public boolean applyStandalonePaymentReversal(Long orderId, Long paymentLinkId, PaymentLinkStatus terminalStatus) {
        return settlementService.applyStandalonePaymentReversal(orderId, paymentLinkId, terminalStatus);
    }

    /**
     * Locks and validates a linked common invoice before a bad-review task can
     * change the payable amount. A frozen, delivered or historical invoice is
     * never silently rewritten; it needs a supplemental invoice or manual
     * reconciliation. The invoice lock is retained by the caller transaction
     * through the later task and amount updates.
     */
    public CommonPayableChangeDisposition prepareLinkedOrderPayableChange(Long orderId) {
        return invoiceMembershipWorkflow.prepareLinkedOrderPayableChange(orderId);
    }

    /**
     * Creates the immutable predecessor's idempotent live successor.
     */
    public boolean createBadReviewSupplementSuccessor(Long orderId, Long taskId) {
        return invoiceMembershipWorkflow.createBadReviewSupplementSuccessor(orderId, taskId);
    }

    public CommonInvoiceDetailsResponse approveReviewOrders(Long invoiceId) {
        return invoiceReviewApprovalWorkflow.approveReviewOrders(invoiceId);
    }

    public CommonInvoiceDetailsResponse detachOrder(Long invoiceId, Long orderId) {
        return invoiceMembershipWorkflow.detachOrder(invoiceId, orderId);
    }

    /**
     * Narrow compatibility path for deleting an untouched order created by the
     * next-order automation after cancellation of its source payment. This is
     * not a hard-delete bypass: it locks and revalidates the order, invoice and
     * membership, and refuses to detach anything carrying payment state.
     */
    public boolean detachOrderForDeletion(Long orderId) {
        return invoiceMembershipWorkflow.detachOrderForDeletion(orderId);
    }

    public CommonInvoiceDetailsResponse changeInvoicePaymentMode(Long invoiceId, InvoicePaymentModeChangeRequest request, Principal principal) {
        return invoiceRouteWorkflow.changeInvoicePaymentMode(invoiceId, request, principal);
    }

    public CommonInvoicePaymentRouteChangeContextResponse commonInvoicePaymentRouteChangeContext(Long invoiceId) {
        return invoiceRouteWorkflow.commonInvoicePaymentRouteChangeContext(invoiceId);
    }

    public CommonInvoiceDetailsResponse changeCommonInvoicePaymentRoute(Long invoiceId, CommonInvoicePaymentRouteChangeRequest request, Principal principal) {
        return invoiceRouteWorkflow.changeCommonInvoicePaymentRoute(invoiceId, request, principal);
    }

    private boolean replaceCommonInvoicePaymentRouteLocked(Long invoiceId, CommonInvoicePaymentRouteChangeRequest request, String actor) {
        return invoiceRouteWorkflow.replaceCommonInvoicePaymentRouteLocked(invoiceId, request, actor);
    }

    private String commonInvoiceRouteChangeBlockReason(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        return invoiceRouteWorkflow.commonInvoiceRouteChangeBlockReason(invoice, paymentRefs);
    }

    private String commonInvoiceRouteChangeBlockReason(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs, boolean preview) {
        return invoiceRouteWorkflow.commonInvoiceRouteChangeBlockReason(invoice, paymentRefs, preview);
    }

    private String commonInvoiceRouteChangeToken(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        return invoiceRouteWorkflow.commonInvoiceRouteChangeToken(invoice, paymentRefs);
    }

    public CommonInvoiceDetailsResponse markPaperInvoiceIssued(Long invoiceId, Principal principal) {
        return invoiceRouteWorkflow.markPaperInvoiceIssued(invoiceId, principal);
    }

    public CommonInvoiceDetailsResponse markPaperInvoicePaid(Long invoiceId, ManualPaymentConfirmationRequest request, Principal principal) {
        return manualPaymentWorkflow.markPaperInvoicePaid(invoiceId, request, principal);
    }

    public CommonInvoiceDetailsResponse markPaid(Long invoiceId, ManualPaymentConfirmationRequest request, Principal principal) {
        return manualPaymentWorkflow.markPaid(invoiceId, request, principal);
    }

    CommonInvoiceDetailsResponse markPaid(Long invoiceId) {
        return manualPaymentWorkflow.markPaid(invoiceId);
    }

    public CommonInvoiceDetailsResponse markPaidWithAttributions(Long invoiceId, CommonManualPaymentAttributionRequest request, Principal principal) {
        return manualPaymentWorkflow.markPaidWithAttributions(invoiceId, request, principal);
    }

    private PreparedManualPaymentBankReconciliation prepareManualPaymentBankReconciliation(Long invoiceId) {
        return manualPaymentWorkflow.prepareManualPaymentBankReconciliation(invoiceId);
    }

    public CommonManualPaymentOptionsResponse manualPaymentOptions(Long invoiceId) {
        return manualPaymentWorkflow.manualPaymentOptions(invoiceId);
    }

    public CommonInvoiceDetailsResponse retryAttention(Long invoiceId) {
        return invoiceRecoveryWorkflow.retryAttention(invoiceId);
    }

    public CommonInvoiceDetailsResponse resolveAttention(Long invoiceId) {
        return invoiceRecoveryWorkflow.resolveAttention(invoiceId);
    }

    public CommonInvoiceDetailsResponse confirmFinalPaymentCancelCheck(Long invoiceId) {
        return invoiceRecoveryWorkflow.confirmFinalPaymentCancelCheck(invoiceId);
    }

    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(Long invoiceId) {
        return invoiceRecoveryWorkflow.confirmPaymentInitCheck(invoiceId);
    }

    public CommonInvoiceDetailsResponse confirmPaymentInitCheck(Long invoiceId, CommonInvoicePaymentInitCheckRequest request) {
        return invoiceRecoveryWorkflow.confirmPaymentInitCheck(invoiceId, request);
    }

    /**
     * Confirms bank-statement evidence against the immutable common-invoice
     * source selected for one contractor recipient. This endpoint deliberately
     * accepts a cumulative total, so retries and partial transfers are
     * idempotent and never get attributed to a newer successor source.
     */
    public CommonInvoiceDetailsResponse confirmContractorPaymentSource(Long invoiceId, long confirmedTotalKopecks, LocalDateTime effectiveAt, String reason, Principal principal) {
        return invoiceRecoveryWorkflow.confirmContractorPaymentSource(invoiceId, confirmedTotalKopecks, effectiveAt, reason, principal);
    }

    public CommonInvoiceDetailsResponse repairStandalonePaymentRouteConflict(Long invoiceId) {
        return invoiceRecoveryWorkflow.repairStandalonePaymentRouteConflict(invoiceId);
    }

    /**
     * Retires an empty collecting shell only when it has no positions and no
     * payment history whatsoever. This recovers an invoice left behind after
     * all auto-created next orders were safely deleted.
     */
    public CommonInvoiceDetailsResponse disableEmptyInvoice(Long invoiceId) {
        return invoiceRecoveryWorkflow.disableEmptyInvoice(invoiceId);
    }

    public CommonInvoiceDetailsResponse reportPaidByManualCardTransfer(Long invoiceId, CommonInvoiceManualCardPaymentRequest request, Principal principal) {
        return manualPaymentWorkflow.reportPaidByManualCardTransfer(invoiceId, request, principal);
    }

    public CommonInvoiceDetailsResponse reportPaidByManualCardTransferWithAttributions(Long invoiceId, CommonManualPaymentAttributionRequest request, Principal principal) {
        return manualPaymentWorkflow.reportPaidByManualCardTransferWithAttributions(invoiceId, request, principal);
    }

    public CommonInvoiceDetailsResponse recoverUnsentPaymentInitTlsFailure(Long invoiceId) {
        return invoiceRecoveryWorkflow.recoverUnsentPaymentInitTlsFailure(invoiceId);
    }

    public CommonInvoiceDetailsResponse resolveTechnicalTail(Long invoiceId) {
        return invoiceRecoveryWorkflow.resolveTechnicalTail(invoiceId);
    }

    public void resolveWhatsappGroupTail(Long invoiceId) {
        invoiceRecoveryWorkflow.resolveWhatsappGroupTail(invoiceId);
    }

    public CommonInvoiceDetailsResponse resolvePaymentSuccessNotification(Long invoiceId) {
        return invoiceRecoveryWorkflow.resolvePaymentSuccessNotification(invoiceId);
    }

    public CommonInvoiceDetailsResponse applyLatePayment(Long invoiceId) {
        return invoiceRecoveryWorkflow.applyLatePayment(invoiceId);
    }

    public CommonInvoiceDetailsResponse markUnpaid(Long invoiceId) {
        return invoiceArchiveWorkflow.markUnpaid(invoiceId);
    }

    public CommonInvoiceArchivePreviewResponse archivePreview(Long invoiceId) {
        return invoiceArchiveWorkflow.archivePreview(invoiceId);
    }

    public CommonInvoiceDetailsResponse archiveInvoice(Long invoiceId, CommonInvoiceCloseRequest request, Principal principal) {
        return invoiceArchiveWorkflow.archiveInvoice(invoiceId, request, principal);
    }

    public CommonInvoiceDetailsResponse restoreLiveArchivedInvoice(Long invoiceId, Principal principal) {
        return invoiceArchiveWorkflow.restoreLiveArchivedInvoice(invoiceId, principal);
    }

    public CommonInvoiceDetailsResponse markBan(Long invoiceId, Principal principal) {
        return invoiceArchiveWorkflow.markBan(invoiceId, principal);
    }

    CommonInvoiceDetailsResponse markBan(Long invoiceId) {
        return invoiceArchiveWorkflow.markBan(invoiceId);
    }

    public PublicPaymentInitResponse initPublicPayment(String token, String email, boolean offerConsent, boolean privacyConsent, boolean receiptConsent) {
        return invoiceInitialization.initPublicPayment(token, email, offerConsent, privacyConsent, receiptConsent);
    }

    private PreparedCommonPaymentInit preparePaymentInit(String token, String cleanEmail) {
        return invoiceInitialization.preparePaymentInit(token, cleanEmail);
    }

    private MappedPayment mapTochkaCreate(PreparedCommonPaymentInit prepared, CreatePaymentResponse response) {
        return invoiceInitialization.mapTochkaCreate(prepared, response);
    }

    private PublicPaymentInitResponse finishTochkaPaymentInit(PreparedCommonPaymentInit prepared, CreatePaymentResponse response, MappedPayment mapped, String paymentUrl) {
        return invoiceInitialization.finishTochkaPaymentInit(prepared, response, mapped, paymentUrl);
    }

    private void failTochkaPaymentInit(PreparedCommonPaymentInit prepared, CreatePaymentResponse response, RuntimeException failure) {
        invoiceInitialization.failTochkaPaymentInit(prepared, response, failure);
    }

    public boolean handleTbankWebhook(Map<String, String> payload) {
        return settlementService.handleTbankWebhook(payload);
    }

    /**
     * Applies an already signature-verified Tochka webhook to a common-invoice attempt.
     */
    public boolean handleTochkaWebhook(TochkaAcquiringInternetPaymentWebhook claims) {
        return settlementService.handleTochkaWebhook(claims);
    }

    private void applyAccountRequest(CommonBillingAccount account, CommonBillingAccountRequest request) {
        accountWorkflow.applyAccountRequest(account, request);
    }

    private Map<Long, List<PaymentLink>> paymentLinksRequiringCommonInvoiceRouteCheck(Map<Long, List<PaymentLink>> paymentLinksByOrder, Collection<CommonInvoiceOrder> items, Set<PaymentLink> appliedStandalonePayments) {
        return manualPaymentWorkflow.paymentLinksRequiringCommonInvoiceRouteCheck(paymentLinksByOrder, items, appliedStandalonePayments);
    }

    private void ensureNoCompetingStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        manualPaymentWorkflow.ensureNoCompetingStandaloneRoutesOrThrow(paymentLinksByOrder);
    }

    private int closeProvablyUnstartedStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder, Long invoiceId) {
        return invoiceInitialization.closeProvablyUnstartedStandaloneRoutesOrThrow(paymentLinksByOrder, invoiceId);
    }

    private Set<PaymentLink> synchronizeConfirmedStandalonePaymentsOrThrow(CommonInvoice invoice, List<CommonInvoiceOrder> items, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        return manualPaymentWorkflow.synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentLinksByOrder);
    }

    private boolean isFrozenManualTaskSource(CommonInvoice invoice) {
        return manualPaymentWorkflow.isFrozenManualTaskSource(invoice);
    }

    private boolean isSafelyClosedStandaloneRoute(PaymentLink link) {
        return manualPaymentWorkflow.isSafelyClosedStandaloneRoute(link);
    }

    private <T> T writeTransaction(Supplier<T> action) {
        return manualPaymentWorkflow.writeTransaction(action);
    }

    private boolean deferReadyCommonInvoiceFinalizationUntilAfterCommit(CommonInvoice invoice) {
        return invoiceMembershipWorkflow.deferReadyCommonInvoiceFinalizationUntilAfterCommit(invoice);
    }

    public int reconcilePendingCompanyLinks(int requestedLimit) {
        return companyReconciliation.reconcilePendingCompanyLinks(requestedLimit);
    }

    private CommonInvoiceOrder attachOrderWithoutInvoiceRefresh(CommonInvoice invoice, Order order) {
        return invoiceMembershipWorkflow.attachOrderWithoutInvoiceRefresh(invoice, order);
    }

    private CommonInvoice createInvoice(CommonBillingAccount account) {
        return invoiceMembershipWorkflow.createInvoice(account);
    }

    private PreparedCommonInvoiceMessage preparePaymentMessage(Long invoiceId, boolean reminder, boolean manual, boolean dueOnly, LocalDateTime dueNow, boolean checkVisibility) {
        return invoiceDelivery.preparePaymentMessage(invoiceId, reminder, manual, dueOnly, dueNow, checkVisibility);
    }

    private ClientMessageSendResult sendPreparedPaymentMessage(PreparedCommonInvoiceMessage prepared) {
        return invoiceDelivery.sendPreparedPaymentMessage(prepared);
    }

    private boolean finishPaymentMessageSend(PreparedCommonInvoiceMessage prepared, ClientMessageSendResult result) {
        return invoiceDelivery.finishPaymentMessageSend(prepared, result);
    }

    private void ensureNoBlockingPaymentRefsForNewInit(CommonInvoice invoice) {
        invoiceInitialization.ensureNoBlockingPaymentRefsForNewInit(invoice);
    }

    private void ensureCommonInvoiceReadyForReminder(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        invoiceDelivery.ensureCommonInvoiceReadyForReminder(invoice, items);
    }

    private String providerOrderId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerOrderId(ref);
    }

    private String providerPaymentId(CommonInvoicePaymentRef ref) {
        return invoiceReconciliation.providerPaymentId(ref);
    }

    private String providerMerchantId(CommonInvoicePaymentRef ref) {
        return CommonInvoicePaymentIdentity.providerMerchantId(ref);
    }

    private void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        manualPaymentWorkflow.closePaidInvoice(invoice, items);
    }

    private void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds) {
        manualPaymentWorkflow.closePaidInvoice(invoice, items, alreadyClosedOrderIds);
    }

    private void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds, Runnable finalAttribution) {
        manualPaymentWorkflow.closePaidInvoice(invoice, items, alreadyClosedOrderIds, finalAttribution);
    }

    private void closeOrderAsPaidForConfirmedItem(CommonInvoice invoice, CommonInvoiceOrder item) {
        settlementService.closeOrderAsPaidForConfirmedItem(invoice, item);
    }

    private void openNextOrdersIfEnabled(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.openNextOrdersIfEnabled(invoice, items);
    }

    /**
     * Performs one provider attempt for the durable common-invoice outbox.
     * Snapshot reads use a short transaction; chat I/O happens only after it
     * has completed. Final durable state is fenced by the outbox claim service.
     */
    public ClientPaymentNotificationAttempt deliverPaymentSuccessNotificationFromOutbox(Long invoiceId) {
        var attempt = invoiceDelivery.deliverPaymentSuccessNotificationFromOutbox(invoiceId);
        return new ClientPaymentNotificationAttempt(attempt.sent(), attempt.skipped(), attempt.channel(), attempt.error());
    }

    private String paymentSuccessMessage(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceDelivery.paymentSuccessMessage(invoice, items);
    }

    private String paymentEvidenceToken(CommonInvoice invoice, List<CommonInvoicePaymentRef> paymentRefs) {
        return invoiceDetailsAssembler.paymentEvidenceToken(invoice, paymentRefs);
    }

    private CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoicePresenter.toInvoiceSummary(invoice, items);
    }

    private CommonInvoiceSummaryResponse toInvoiceSummary(CommonInvoice invoice, List<CommonInvoiceOrder> items, String tbankTerminalLabel) {
        return invoicePresenter.toInvoiceSummary(invoice, items, tbankTerminalLabel);
    }

    private void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        manualPaymentWorkflow.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    private String paymentRouteChangedMessage(String invoiceMessage) {
        return invoiceDelivery.paymentRouteChangedMessage(invoiceMessage);
    }

    private void ensureCommonPaymentRouteSelected(CommonInvoice invoice, long remainingKopecks) {
        invoiceInitialization.ensureCommonPaymentRouteSelected(invoice, remainingKopecks);
    }

    private void scheduleContractorShadowRoute(Long invoiceId, String routeGeneration) {
        invoiceInitialization.scheduleContractorShadowRoute(invoiceId, routeGeneration);
    }

    private PaymentProfile lockedCommonPaymentProfile(CommonInvoice invoice) {
        return invoiceInitialization.lockedCommonPaymentProfile(invoice);
    }

    private Manager manager(CommonInvoice invoice) {
        return invoiceInitialization.manager(invoice);
    }

    private Manager manager(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceInitialization.manager(invoice, items);
    }

    private LocalDateTime nextAutomaticPaymentReminderAt(LocalDateTime from) {
        return settlementService.nextAutomaticPaymentReminderAt(from);
    }

    private long amountKopecks(BigDecimal amount) {
        return settlementService.amountKopecks(amount);
    }

    private String managerName(Manager manager) {
        return accountWorkflow.managerName(manager);
    }

    public record ClientPaymentNotificationAttempt(boolean sent, boolean skipped, String channel, String error) {
    }

    public record ManagerBoardPage(List<OrderDTOList> cards, long totalCards, int linkedOrderCount) {

        public ManagerBoardPage {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }
    }

    public record ManagerBoardMetrics(Map<String, Integer> cardCounts, Map<String, Integer> linkedOrderCounts) {

        public ManagerBoardMetrics {
            cardCounts = cardCounts == null ? Map.of() : Map.copyOf(cardCounts);
            linkedOrderCounts = linkedOrderCounts == null ? Map.of() : Map.copyOf(linkedOrderCounts);
        }
    }
}
