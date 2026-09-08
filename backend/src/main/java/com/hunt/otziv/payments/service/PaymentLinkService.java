package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentContextResponse;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.AdminPaymentLinksPageResponse;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.ManagerManualCardPaymentResultResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeContextResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import com.hunt.otziv.payments.dto.PaymentLinkArchiveRunResponse;
import com.hunt.otziv.payments.dto.PaymentRouteSelection;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PublicSbpBankResponse;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.PaymentBankObservationService.ProviderStateObservation;

/** Compatibility entry points and DTOs; payment scenarios and transaction ownership live in scoped workflows. */
@Service
@RequiredArgsConstructor
public class PaymentLinkService implements com.hunt.otziv.payments.api.StandalonePaymentOperations {

    private final PaymentLinkAmountPolicy amountPolicy;

    private final PaymentLinkSettlementService settlementService;

    private final ManualPaymentConfirmationWorkflow manualConfirmationWorkflow;

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final PaymentLinkInitializationWorkflow initializationWorkflow;

    private final ManualCardPaymentWorkflow manualCardPayments;

    private final OwnerManualCardApprovalWorkflow ownerManualCardApproval;

    private final BankPaymentReconciliationWorkflow bankPaymentReconciliation;

    private final OrderPaymentLinkWorkflow orderPaymentLinks;

    private final PublicPaymentPageWorkflow publicPaymentPages;

    private final PaymentRouteReplacementWorkflow paymentRouteReplacement;

    private final PaymentLinkAdminBoardWorkflow paymentAdminBoard;

    private final PaymentLinkCancellationWorkflow cancellationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentLinkArchiveService paymentLinkArchiveService;

    private final ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    @Transactional
    public ManagerPaymentLinkResponse createForOrder(Long orderId) {
        return preparationWorkflow.createForOrder(orderId);
    }

    public ManagerPaymentLinkResponse createForOrderInNewTransaction(Long orderId) {
        return preparationWorkflow.createForOrderInNewTransaction(orderId);
    }

    /**
     * Manager-facing entry point. The current order row is locked before the
     * object-scope check, so a concurrent reassignment cannot invalidate the
     * authorization between the check and payment-link creation.
     */
    @Transactional
    public ManagerPaymentLinkResponse createForOrderAuthorized(Long orderId, Authentication authentication) {
        return preparationWorkflow.createForOrderAuthorized(orderId, authentication);
    }

    @Transactional
    public PaymentInstructionPreparation prepareForOrderAuthorized(Long orderId, Authentication authentication) {
        var prepared = preparationWorkflow.prepareForOrderAuthorized(orderId, authentication);
        return new PaymentInstructionPreparation(prepared.response(), prepared.createdFresh());
    }

    @Transactional
    public ManagerPaymentLinkResponse markPaperInvoiceIssuedAuthorized(Long orderId, Authentication authentication) {
        return preparationWorkflow.markPaperInvoiceIssuedAuthorized(orderId, authentication);
    }

    @Transactional(readOnly = true)
    public PaymentRouteChangeContextResponse paymentRouteChangeContextAuthorized(Long orderId, Authentication authentication) {
        return paymentRouteReplacement.paymentRouteChangeContextAuthorized(orderId, authentication);
    }

    @Transactional
    public PaymentRouteReplacement replacePaymentRouteAuthorized(Long orderId, Long expectedPaymentLinkId, PaymentRouteChangeTarget target, boolean confirmedUnpaid, Long expectedTargetPaymentProfileId, Authentication authentication) {
        var replacement = paymentRouteReplacement.replacePaymentRouteAuthorized(orderId, expectedPaymentLinkId, target, confirmedUnpaid, expectedTargetPaymentProfileId, authentication);
        return new PaymentRouteReplacement(replacement.previousPaymentLinkId(), replacement.paymentLinkId(), replacement.target(), replacement.response());
    }

    public record PaymentRouteReplacement(Long previousPaymentLinkId, Long paymentLinkId, PaymentRouteChangeTarget target, ManagerPaymentLinkResponse response) {
    }

    /**
     * Releases only the exact pristine source created for a definitely-unsent external message.
     */
    @Transactional
    public boolean cancelFreshUnsentPreparationAuthorized(String token, Long orderId, Authentication authentication) {
        return preparationWorkflow.cancelFreshUnsentPreparationAuthorized(token, orderId, authentication);
    }

    private ManagerPaymentLinkResponse toManagerResponseWithShadowRoute(PaymentLink link) {
        return preparationWorkflow.toManagerResponseWithShadowRoute(link);
    }

    /**
     * Rebuilds delivery text from the frozen payment-link/allocation route.
     * Decrypted contractor requisites therefore never need to be copied into
     * the notification outbox in plaintext.
     */
    @Transactional(readOnly = true)
    public ManagerPaymentLinkResponse paymentRouteChangeNotificationDetails(Long paymentLinkId) {
        return paymentRouteReplacement.paymentRouteChangeNotificationDetails(paymentLinkId);
    }

    private InvoicePaymentMode paymentModeForTarget(PaymentRouteChangeTarget target) {
        return paymentRouteReplacement.paymentModeForTarget(target);
    }

    public PaymentRouteSelection selectCommonInvoiceRoute(Manager manager, long amountKopecks) {
        return commonInvoiceRouteSelector.selectCommonInvoiceRoute(manager, amountKopecks);
    }

    public PaymentRouteSelection selectCommonInvoiceOwnerAcquiringRoute(Manager manager, long amountKopecks) {
        return commonInvoiceRouteSelector.selectCommonInvoiceOwnerAcquiringRoute(manager, amountKopecks);
    }

    public Optional<PaymentRouteSelection> selectCommonInvoiceTaskRoute(com.hunt.otziv.common_billing.model.CommonInvoice invoice, Manager manager, long amountKopecks) {
        return commonInvoiceRouteSelector.selectCommonInvoiceTaskRoute(invoice, manager, amountKopecks);
    }

    @Transactional
    public int expireStaleLinksForOrder(Long orderId) {
        return orderPaymentLinks.expireStaleLinksForOrder(orderId);
    }

    /**
     * Fail-closed preflight for a task price entering or leaving an order.
     * Only a route with no client/bank evidence can be retired. The caller's
     * transaction keeps the order and link locks until the task, reward and
     * payable amount have changed together.
     */
    @Transactional
    public int retireOpenLinksBeforePayableChange(Long orderId, String reason) {
        return orderPaymentLinks.retireOpenLinksBeforePayableChange(orderId, reason);
    }

    /**
     * Refreshes the bank state of the current payment before an explicit
     * automation retry. A payment that has already finished is applied through
     * the normal bank-status path; an unstarted stale link may be retired. A
     * genuinely active payment is deliberately left untouched so the retry
     * cannot create a duplicate charge.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcileActiveOrder(Long orderId) {
        orderPaymentLinks.reconcileActiveOrder(orderId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentLinkReconcileResult reconcileActiveLinkForOrder(Long orderId) {
        var result = orderPaymentLinks.reconcileActiveLinkForOrder(orderId);
        return new PaymentLinkReconcileResult(result.linkId(), result.statusBefore(), result.statusAfter(), result.changed());
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
        return orderPaymentLinks.refreshLinkedOrderAmount(orderId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentLinkResponse publicLink(String token) {
        return publicPaymentPages.publicLink(token);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<PublicSbpBankResponse> publicSbpBanks(String token, String deviceType, String os) {
        return publicPaymentPages.publicSbpBanks(token, deviceType, os);
    }

    @Transactional
    public AdminPaymentLinksPageResponse adminLinks(int page, int size, String statusFilter, String search, LocalDate from, LocalDate to, String source) {
        return paymentAdminBoard.adminLinks(page, size, statusFilter, search, from, to, source);
    }

    @Transactional
    public AdminPaymentLinksPageResponse adminLinks(int page, int size, String statusFilter, String search, LocalDate from, LocalDate to, String source, String sortDirection) {
        return paymentAdminBoard.adminLinks(page, size, statusFilter, search, from, to, source, sortDirection);
    }

    @Transactional
    public PaymentLinkArchiveRunResponse archiveClosedLinks(boolean dryRun, Integer batchSize) {
        contractorPaymentTargetAccessPolicy.requireCanManageAllPaymentLinks();
        return paymentLinkArchiveService.run(dryRun, batchSize);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse cancel(Long linkId) {
        return cancellationWorkflow.cancel(linkId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManualCardPaymentContextResponse manualCardPaymentContextForOrder(Long orderId, Authentication authentication) {
        return manualCardPayments.manualCardPaymentContextForOrder(orderId, authentication);
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
        return manualCardPayments.confirmPaidByManualCardTransferForOrder(orderId, recipientStatementChecked, paymentReceived, receivedAmountKopecks, note, receiptUrl, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse reportPaidByManualCardTransferForOrder(Long orderId, String reason, String actor, Authentication authentication) {
        return manualCardPayments.reportPaidByManualCardTransferForOrder(orderId, reason, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse reportPaidByManualCardTransferForOrder(Long orderId, String reason, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId, String recipientKey, String actor, Authentication authentication) {
        return manualCardPayments.reportPaidByManualCardTransferForOrder(orderId, reason, receiptUrl, recipientType, recipientProfileId, recipientKey, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManagerManualCardPaymentResultResponse submitManagerManualCardPaymentForOrder(Long orderId, String reason, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId, String recipientKey, String actor, Authentication authentication) {
        return ownerManualCardApproval.submitManagerManualCardPaymentForOrder(orderId, reason, receiptUrl, recipientType, recipientProfileId, recipientKey, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OwnerManualCardPaymentApprovalOutcome approveOwnerManualCardPayment(Long approvalId, String callbackToken, Long callbackChatId, User approver, Authentication authentication) {
        var outcome = ownerManualCardApproval.approveOwnerManualCardPayment(approvalId, callbackToken, callbackChatId, approver, authentication);
        return new OwnerManualCardPaymentApprovalOutcome(outcome.approvalId(), outcome.orderId(), outcome.paymentLinkId(), outcome.amountKopecks(), outcome.alreadyCompleted());
    }

    public record OwnerManualCardPaymentApprovalOutcome(Long approvalId, Long orderId, Long paymentLinkId, long amountKopecks, boolean alreadyCompleted) {
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmPaidByManualCardTransfer(Long linkId, boolean recipientStatementChecked, boolean paymentReceived, Long receivedAmountKopecks, String note, String receiptUrl, String actor, Authentication authentication) {
        return manualCardPayments.confirmPaidByManualCardTransfer(linkId, recipientStatementChecked, paymentReceived, receivedAmountKopecks, note, receiptUrl, actor, authentication);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmPaidByManualCardTransfer(Long linkId, boolean recipientStatementChecked, boolean paymentReceived, Long receivedAmountKopecks, String note, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId, String recipientKey, String actor, Authentication authentication) {
        return manualCardPayments.confirmPaidByManualCardTransfer(linkId, recipientStatementChecked, paymentReceived, receivedAmountKopecks, note, receiptUrl, recipientType, recipientProfileId, recipientKey, actor, authentication);
    }

    private void cancelBadReviewAutoBanAfterCommit(Order order, String reason) {
        settlementService.cancelBadReviewAutoBanAfterCommit(order, reason);
    }

    /**
     * Recovery for the intentionally conservative no-PaymentId quarantine.
     * The endpoint requires an explicit administrator assertion that the
     * stable T-Bank OrderId was checked and no payment exists.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public AdminPaymentLinkResponse releaseAmbiguousBankInit(Long linkId, boolean bankPaymentAbsent, String note, String actor) {
        return initializationWorkflow.releaseAmbiguousBankInit(linkId, bankPaymentAbsent, note, actor);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmManual(Long linkId, String confirmedBy) {
        return manualConfirmationWorkflow.confirmManual(linkId, confirmedBy);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminPaymentLinkResponse confirmContractorPaymentSource(Long linkId, long confirmedTotalKopecks, LocalDateTime effectiveAt, String reason, String confirmedBy) {
        return manualConfirmationWorkflow.confirmContractorPaymentSource(linkId, confirmedTotalKopecks, effectiveAt, reason, confirmedBy);
    }

    /**
     * Retires only the selected manual payment instruction after an operator
     * has checked the recipient statement and explicitly asserted that the
     * transfer is absent. This operation deliberately does not mutate the
     * order status and does not apply any payment to a common invoice.
     */
    @Transactional
    public AdminPaymentLinkResponse closeManualAsUnpaid(Long linkId, boolean recipientStatementChecked, boolean paymentAbsent, String note, String actor, Authentication authentication) {
        return manualConfirmationWorkflow.closeManualAsUnpaid(linkId, recipientStatementChecked, paymentAbsent, note, actor, authentication);
    }

    @Transactional
    public AdminPaymentLinkResponse markManualReceipt(Long linkId, String confirmedBy) {
        return manualConfirmationWorkflow.markManualReceipt(linkId, confirmedBy);
    }

    @Transactional
    public AdminPaymentLinkResponse markManualReceiptLegacyNotRequired(Long linkId, String confirmedBy) {
        return manualConfirmationWorkflow.markManualReceiptLegacyNotRequired(linkId, confirmedBy);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public PublicPaymentLinkResponse reportManualPayment(String token) {
        return publicPaymentPages.reportManualPayment(token);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentInitResponse init(String token, String email, boolean offerConsent, boolean privacyConsent, boolean receiptConsent, String clientIp, String userAgent) {
        return initializationWorkflow.init(token, email, offerConsent, privacyConsent, receiptConsent, clientIp, userAgent);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicPaymentInitResponse initSbp(String token, String email, boolean offerConsent, boolean privacyConsent, boolean receiptConsent, String sbpBankId, String clientIp, String userAgent) {
        return initializationWorkflow.initSbp(token, email, offerConsent, privacyConsent, receiptConsent, sbpBankId, clientIp, userAgent);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void handleTbankWebhook(Map<String, String> payload) {
        bankPaymentReconciliation.handleTbankWebhook(payload);
    }

    /**
     * Applies a signed Tochka acquiring webhook to one exact, already-created payment attempt.
     * Signature verification intentionally happens before the first repository lookup.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void handleTochkaWebhook(String rawJwt) {
        bankPaymentReconciliation.handleTochkaWebhook(rawJwt);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reconcileBankLink(Long linkId) {
        return bankPaymentReconciliation.reconcileBankLink(linkId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean recoverExpiredBankInitReservation(Long linkId, LocalDateTime expiredBefore) {
        return initializationWorkflow.recoverExpiredBankInitReservation(linkId, expiredBefore);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reconcileBankLink(Long linkId, LocalDateTime attemptBefore) {
        return bankPaymentReconciliation.reconcileBankLink(linkId, attemptBefore);
    }

    private String providerStatus(ProviderStateObservation observation) {
        return bankPaymentReconciliation.providerStatus(observation);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean applyConfirmedPrepaymentIfReady(Long orderId) {
        return settlementService.applyConfirmedPrepaymentIfReady(orderId);
    }

    @Transactional
    public boolean applyConfirmedPrepaymentIfReady(Order order) {
        return settlementService.applyConfirmedPrepaymentIfReady(order);
    }

    private PublicPaymentLinkResponse toPublicResponse(PaymentLink link) {
        return paymentPresenter.toPublicResponse(link);
    }

    private AdminPaymentLinkResponse toAdminResponse(PaymentLink link) {
        return paymentPresenter.toAdminResponse(link);
    }

    private void applyManualTaskPayment(PaymentLink link, ManualPaymentTask task) {
        preparationWorkflow.applyManualTaskPayment(link, task);
    }

    private void applyManualTaskPayment(PaymentLink link, ManualPaymentTaskRouteSnapshot snapshot) {
        preparationWorkflow.applyManualTaskPayment(link, snapshot);
    }

    private long amountKopecks(BigDecimal amount) {
        return amountPolicy.amountKopecks(amount);
    }

    private String description(Order order) {
        return preparationWorkflow.description(order);
    }

    public record PaymentInstructionPreparation(ManagerPaymentLinkResponse response, boolean createdFresh) {
    }

    public record PaymentLinkReconcileResult(Long linkId, PaymentLinkStatus statusBefore, PaymentLinkStatus statusAfter, boolean changed) {
    }

}
