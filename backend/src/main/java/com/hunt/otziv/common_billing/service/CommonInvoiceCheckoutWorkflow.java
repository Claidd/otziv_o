package com.hunt.otziv.common_billing.service;

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
import com.hunt.otziv.common_billing.dto.CommonInvoiceOrderResponse;
import com.hunt.otziv.common_billing.dto.PublicCommonInvoiceResponse;
import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.service.PaymentUrlPolicy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

/** Owns anonymous capability reads and the client's payment report with the existing token and route guards. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceCheckoutWorkflow {

    private final CommonInvoiceBoardWorkflow invoiceBoardWorkflow;

    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;

    private final CommonInvoiceDetailsAssembler invoiceDetailsAssembler;

    private final CommonInvoiceInitializationService invoiceInitialization;

    private final CommonInvoiceSettlementService settlementService;

    private final EntityManager entityManager;

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    @Transactional
    public PublicCommonInvoiceResponse publicInvoice(String token) {
        CommonInvoice invoice = lockedInvoiceByToken(cleanToken(token)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        refreshInvoiceAmounts(invoice, items);
        long remaining = remainingKopecks(invoice);
        boolean payable = remaining > 0 && canAcceptPublicPayment(invoice);
        if (payable) {
            ensureCommonPaymentRouteSelected(invoice, remaining);
        }
        boolean contractorRoute = invoice.getContractorAllocationId() != null && invoice.getPaymentRouteManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE;
        ContractorPaymentRequisitesSnapshot contractorRequisites = contractorRoute && payable ? contractorPaymentLiveRoutingService.activeCommonInvoiceRequisites(invoice, remaining).orElse(null) : null;
        boolean contractorRequisitesVisible = !contractorRoute || contractorRequisites != null;
        String manualPhone = contractorRoute ? contractorRequisites == null ? "" : normalize(contractorRequisites.paymentPhone()) : normalize(invoice.getPaymentRouteManualPhone());
        String manualRecipient = contractorRoute ? contractorRequisites == null ? "" : normalize(contractorRequisites.recipientName()) : normalize(invoice.getPaymentRouteManualRecipient());
        String manualBank = contractorRoute ? contractorRequisites == null ? "" : normalize(contractorRequisites.bankName()) : normalize(invoice.getPaymentRouteManualBankName());
        String manualComment = contractorRoute ? contractorRequisites == null ? "" : normalize(contractorRequisites.paymentComment()) : normalize(invoice.getPaymentRouteManualComment());
        return new PublicCommonInvoiceResponse(invoice.getToken(), invoice.getTitle(), invoice.getAccount().getName(), effectiveInvoiceStatus(invoice, items).name(), amountRubles(invoice.getAmountKopecks()), amountRubles(invoice.getPaidKopecks()), amountRubles(remaining), invoice.getAmountKopecks(), invoice.getPaidKopecks(), remaining, payable, normalize(invoice.getPaymentRouteType()), invoice.getPaymentRouteManualType() == null ? "" : invoice.getPaymentRouteManualType().name(), contractorRequisitesVisible ? manualPhone : "", contractorRequisitesVisible ? manualRecipient : "", contractorRequisitesVisible ? manualBank : "", contractorRequisitesVisible && !contractorRoute ? safeCommonManualPaymentUrl(invoice) : "", normalize(invoice.getPaymentRouteManualButton()), contractorRequisitesVisible ? manualComment : "", contractorRequisitesVisible && !contractorRoute ? normalize(invoice.getPaymentRouteInstructionText()) : "", payable && contractorRoute && contractorRequisitesVisible && contractorPaymentLiveRoutingService.isCommonClientReportable(invoice), invoice.getClientReportedAt(), items.stream().map(this::toOrderResponse).toList());
    }

    /**
     * Token-only, idempotent public evidence; no amount or recipient is accepted from the client.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PublicCommonInvoiceResponse reportPublicCommonPayment(String token) {
        String clean = cleanToken(token);
        contractorPaymentLiveRoutingService.recordCommonClientReported(clean);
        return writeTransaction(() -> publicInvoice(clean));
    }

    Optional<CommonInvoice> lockedInvoiceByToken(String token) {
        Set<Long> lockedOrderIds = lockInvoiceOrderAggregatesByToken(token);
        Optional<CommonInvoice> snapshot = invoiceRepository.findByTokenWithAccount(token);
        Long expectedAccountId = snapshot.map(CommonInvoice::getAccount).map(CommonBillingAccount::getId).orElse(null);
        lockFreshAccountAfterOrderPrelude(expectedAccountId);
        Optional<CommonInvoice> invoice = invoiceRepository.findByTokenWithAccountForUpdate(token).or(() -> snapshot);
        invoice.ifPresent(locked -> {
            entityManager.refresh(locked);
            ensureInvoiceAccountUnchanged(locked, expectedAccountId);
        });
        invoice.ifPresent(locked -> ensureInvoiceMembershipUnchanged(locked.getId(), lockedOrderIds));
        return invoice;
    }

    CommonBillingAccount lockFreshAccountAfterOrderPrelude(Long accountId) {
        return settlementService.lockFreshAccountAfterOrderPrelude(accountId);
    }

    void ensureInvoiceAccountUnchanged(CommonInvoice invoice, Long expectedAccountId) {
        settlementService.ensureInvoiceAccountUnchanged(invoice, expectedAccountId);
    }

    Set<Long> lockInvoiceOrderAggregatesByToken(String token) {
        return invoiceInitialization.lockInvoiceOrderAggregatesByToken(token);
    }

    void ensureInvoiceMembershipUnchanged(Long invoiceId, Set<Long> lockedOrderIds) {
        settlementService.ensureInvoiceMembershipUnchanged(invoiceId, lockedOrderIds);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return manualPaymentWorkflow.writeTransaction(action);
    }

    boolean canAcceptPublicPayment(CommonInvoice invoice) {
        return invoiceInitialization.canAcceptPublicPayment(invoice);
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        manualPaymentWorkflow.refreshInvoiceAmounts(invoice, items);
    }

    CommonInvoiceStatus effectiveInvoiceStatus(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceBoardWorkflow.effectiveInvoiceStatus(invoice, items);
    }

    CommonInvoiceOrderResponse toOrderResponse(CommonInvoiceOrder item) {
        return invoiceDetailsAssembler.toOrderResponse(item);
    }

    void ensureCommonPaymentRouteSelected(CommonInvoice invoice, long remainingKopecks) {
        invoiceInitialization.ensureCommonPaymentRouteSelected(invoice, remainingKopecks);
    }

    String safeCommonManualPaymentUrl(CommonInvoice invoice) {
        return PaymentUrlPolicy.safe(invoice == null ? null : invoice.getPaymentRouteManualUrl(), PaymentUrlPolicy.Purpose.MANUAL_EXTERNAL);
    }

    BigDecimal amountRubles(long kopecks) {
        return settlementService.amountRubles(kopecks);
    }

    long remainingKopecks(CommonInvoice invoice) {
        return manualPaymentWorkflow.remainingKopecks(invoice);
    }

    String cleanToken(String token) {
        return invoiceInitialization.cleanToken(token);
    }

    String normalize(String value) {
        return manualPaymentWorkflow.normalize(value);
    }
}
