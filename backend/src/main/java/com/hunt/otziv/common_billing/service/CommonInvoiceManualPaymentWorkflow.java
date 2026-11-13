package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoiceTochkaReconciliationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDeliveryService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceDetailsAssembler.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceInitializationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.*;
import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOptionsResponse;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentOtherRecipientsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceManualCardPaymentRequest;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionFlowPolicy;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.service.ManualCardPaymentReviewNotificationService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceManualPaymentWorkflow {

    private final com.hunt.otziv.payments.api.StandalonePaymentOperations standalonePaymentOperations;

    private final CommonInvoiceCancellationService invoiceCancellation;

    private final CommonInvoiceDeliveryService invoiceDelivery;

    private final CommonInvoiceDetailsAssembler invoiceDetailsAssembler;

    private final CommonInvoiceSettlementService settlementService;

    static final String PAYMENT_METHOD_OWNER_PAPER_INVOICE = "OWNER_PAPER_INVOICE";

    private void requireNoIntegratedPaperDocument(CommonInvoice invoice) {
        if (invoice.getPaperBankDocumentId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплата этого счёта подтверждается по банковскому поступлению");
        }
    }

    static final Set<PaymentLinkStatus> MANUAL_COMMON_PAYMENT_CLOSABLE_ROUTE_STATUSES = Set.of(PaymentLinkStatus.CREATED, PaymentLinkStatus.WAITING_MANUAL_PAYMENT, PaymentLinkStatus.MANUAL_REPORTED, PaymentLinkStatus.EXPIRED, PaymentLinkStatus.FAILED);

    static final Set<String> MANUAL_COMMON_PAYMENT_SAFE_REF_STATUSES = Set.of(PAYMENT_REF_APPLIED, PAYMENT_REF_ARCHIVED, PAYMENT_REF_CANCELED, "REJECTED", "EXPIRED", "REFUNDED", "REVERSED");

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final PaymentLinkRepository paymentLinkRepository;

    private final ManualCardPaymentReviewNotificationService manualCardPaymentReviewNotificationService;

    private final CommonManualPaymentAttributionCoordinator commonManualPaymentAttributionCoordinator;

    private final ContractorActualPaymentAttributionFlowPolicy actualPaymentAttributionFlowPolicy;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final PaymentProfileService paymentProfileService;

    private final TbankClient tbankClient;

    private final CommonOwnerReceiptApprovals ownerReceipts;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private CommonInvoicePaymentRouteWorkflow paymentRoutes;

    @org.springframework.beans.factory.annotation.Autowired
    private com.hunt.otziv.payments.api.UnpaidBankObservation bankObservation;

    CommonInvoiceDetailsResponse invoiceAfterOrderPrelude(Long invoiceId) {
        CommonInvoice invoice = lockedInvoiceAfterOrderPrelude(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        return invoiceDetails(invoice, items);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markPaperInvoicePaid(Long invoiceId, ManualPaymentConfirmationRequest request, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        if (invoice.getPaperBankDocumentId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплата этого счёта подтверждается по банковскому поступлению");
        }
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (!isOwnerPaperInvoice(invoice) || invoice.getPaperInvoiceIssuedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Подтвердить оплату можно только после отправки бумажного счёта");
        }
        ensureCommonInvoiceNotNeedsAttention(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        promoteCollectingInvoiceToReadyIfPossible(invoice, items);
        ensureCommonInvoiceCanBeMarkedPaid(invoice);
        ensurePaperModeSwitchHasNoFinancialEvidence(invoice);
        ensurePaperModeSwitchHasNoUnresolvedProviderEvidence(invoice);
        applyManualPaymentEvidence(invoice, items, request, principal);
        invoice.setPaymentMethod(PAYMENT_METHOD_OWNER_PAPER_INVOICE);
        items.forEach(item -> item.setPaymentMethod(PAYMENT_METHOD_OWNER_PAPER_INVOICE));
        invoiceOrderRepository.saveAll(items);
        archiveAndClearCurrentPaymentRef(invoice, "owner_paper_invoice_paid");
        closePaidInvoice(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    /** Internal settlement for a signature-verified, deduplicated receipt, including delivery failures. */
    @Transactional
    public void applyVerifiedPaperBankPayment(Long invoiceId, long amountKopecks, String paymentId) {
        var prelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        var invoice = prelude.invoice();
        var items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        if (!isOwnerPaperInvoice(invoice) || invoice.getAmountKopecks() != amountKopecks
                || invoice.getPaidKopecks() != 0 || items.isEmpty() || items.stream().anyMatch(item -> !item.isReady() || item.isPaid())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав или сумма общего счёта изменились; нужна сверка");
        }
        ensureCommonInvoiceNotNeedsAttention(invoice);
        promoteCollectingInvoiceToReadyIfPossible(invoice, items);
        ensureCommonInvoiceCanBeMarkedPaid(invoice);
        ensurePaperModeSwitchHasNoFinancialEvidence(invoice);
        ensurePaperModeSwitchHasNoUnresolvedProviderEvidence(invoice);
        applyManualPaymentEvidence(invoice, items,
                new ManualPaymentConfirmationRequest("Точка: банковский перевод " + paymentId, ""), () -> "tochka-bank");
        invoice.setPaymentMethod(PAYMENT_METHOD_OWNER_PAPER_INVOICE);
        items.forEach(item -> item.setPaymentMethod(PAYMENT_METHOD_OWNER_PAPER_INVOICE));
        invoiceOrderRepository.saveAll(items);
        archiveAndClearCurrentPaymentRef(invoice, "tochka_paper_invoice_paid");
        closePaidInvoice(invoice, items);
    }

    void ensurePaperModeSwitchHasNoFinancialEvidence(CommonInvoice invoice) {
        boolean providerEvidence = invoice.getClientReportedAt() != null || invoice.getPaidKopecks() > 0 || invoice.getManualConfirmedAt() != null;
        if (providerEvidence) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "По общему счёту уже есть признаки оплаты; сначала выполните сверку");
        }
    }

    void ensurePaperModeSwitchHasNoUnresolvedProviderEvidence(CommonInvoice invoice) {
        boolean incompleteCurrentBinding = !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankTerminalKey()).isBlank() || invoice.getTbankPaymentAmountKopecks() != null || invoice.getTbankPaymentCreatedAt() != null || !normalize(invoice.getPaymentUrl()).isBlank();
        boolean unresolvedRegistry = paymentRefRepository.existsByInvoice_IdAndStatusIn(invoice.getId(), Set.of(PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CURRENT, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCEL_FAILED_FINAL, PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING, PAYMENT_REF_APPLIED));
        if (incompleteCurrentBinding || unresolvedRegistry) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "По общему счёту есть незавершённая банковская попытка. Бумажный режим не включён; нужна сверка");
        }
    }

    boolean hasCurrentTbankPaymentBinding(CommonInvoice invoice) {
        return invoice != null && !normalize(invoice.getTbankPaymentId()).isBlank() && !normalize(invoice.getTbankTerminalKey()).isBlank() && invoice.getTbankPaymentAmountKopecks() != null && invoice.getTbankPaymentAmountKopecks() > 0;
    }

    CommonInvoicePaymentRef currentPaymentRefForPaperModeSwitch(CommonInvoice invoice) {
        CommonInvoicePaymentRef paymentRef = lockedPaymentRefByProviderBinding(invoice.getTbankOrderId(), invoice.getTbankPaymentId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "У прежней T-Bank-сессии отсутствует запись реестра. Бумажный режим не включён; нужна сверка"));
        if (!Objects.equals(invoice.getId(), paymentRefInvoiceId(paymentRef))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Прежняя T-Bank-сессия относится к другому общему счёту; нужна сверка");
        }
        return paymentRef;
    }

    boolean isPaperModeSwitchSafeTerminalStatus(String status) {
        return settlementService.isPaperModeSwitchSafeTerminalStatus(status);
    }

    boolean isPaperModeSwitchFinancialStatus(String status) {
        return settlementService.isPaperModeSwitchFinancialStatus(status);
    }

    String readableProviderStatus(String status) {
        return settlementService.readableProviderStatus(status);
    }

    String maskPaymentId(String paymentId) {
        return CommonInvoicePaymentIdentity.maskPaymentId(paymentId);
    }

    boolean isOwnerPaperInvoice(CommonInvoice invoice) {
        return CommonInvoiceRouteState.isOwnerPaperInvoice(invoice);
    }

    @Transactional
    public CommonInvoiceDetailsResponse markPaid(Long invoiceId, ManualPaymentConfirmationRequest request, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        requireNoIntegratedPaperDocument(invoice);
        actualPaymentAttributionFlowPolicy.requireLegacyFlowLocked();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureGenericConfirmationDoesNotUseContractorSource(invoice);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID || invoice.getStatus() == CommonInvoiceStatus.DISABLED || invoice.getStatus() == CommonInvoiceStatus.BAN) {
            ensureCommonInvoiceCanBeMarkedPaid(invoice);
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        promoteCollectingInvoiceToReadyIfPossible(invoice, items);
        ensureCommonInvoiceCanBeMarkedPaid(invoice);
        applyManualPaymentEvidence(invoice, items, request, principal);
        archiveAndClearCurrentPaymentRef(invoice, "manual_paid");
        closePaidInvoice(invoice, items);
        return invoiceAfterOrderPrelude(invoiceId);
    }

    CommonInvoiceDetailsResponse markPaid(Long invoiceId) {
        return markPaid(invoiceId, new ManualPaymentConfirmationRequest("Внутреннее подтверждение", ""), () -> "system");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse markPaidWithAttributions(Long invoiceId, CommonManualPaymentAttributionRequest submitted, Principal principal) {
        CommonManualPaymentAttributionRequest request = withServerReceiptTime(invoiceId, submitted);
        validateCommonManualPaymentAttributionRequest(request);
        if (stageOwnerReport(invoiceId, request, false, principal)) return writeTransaction(() -> invoiceAfterOrderPrelude(invoiceId));
        var source = writeTransaction(() -> currentTochkaReceiptSource(invoiceId, request));
        if (source != null) {
            var result = bankObservation.observe(source);
            if (!result.openUnpaid(source) && result.outcome() != com.hunt.otziv.payments.api.UnpaidBankObservation.Outcome.CLOSED)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Точка ещё не подтвердила отсутствие оплаты. Статус " + result.status());
            return writeTransaction(() -> {
                if (!source.equals(currentTochkaReceiptSource(invoiceId, request)))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "За время проверки изменились счёт или банковская ссылка");
                var invoice = lockedInvoice(invoiceId).orElseThrow();
                var ref = paymentRefRepository.findByIdForUpdate(source.sourceId()).orElseThrow();
                ref.setStatus(result.openUnpaid(source) ? PAYMENT_REF_ARCHIVED : "EXPIRED");
                ref.setObserveReplacedBank(result.openUnpaid(source));
                ref.setProviderStatus(result.status());
                ref.setReason(result.openUnpaid(source) ? "actual_recipient_received_observe_only" : "manual_payment_bank_session_closed");
                clearCurrentPaymentRef(invoice);
                return markPaidWithAttributionsLocked(invoiceId, request, principal);
            });
        }
        PreparedManualPaymentBankReconciliation prepared = writeTransaction(() -> prepareManualPaymentBankReconciliation(invoiceId, request));
        if (prepared.providerPaymentRefId() == null) {
            return writeTransaction(() -> markPaidWithAttributionsLocked(invoiceId, request, principal));
        }
        PaperInvoiceBankResolution resolution = normalize(prepared.knownProviderStatus()).isBlank() ? resolveManualPaymentBankSession(prepared) : isPaperModeSwitchFinancialStatus(prepared.knownProviderStatus()) ? PaperInvoiceBankResolution.blockedByPayment(prepared.knownProviderStatus()) : PaperInvoiceBankResolution.safe(prepared.knownProviderStatus());
        if (!resolution.switchAllowed()) {
            ManualPaymentBankReconciliationResult result = writeTransaction(() -> finishManualPaymentBankReconciliation(prepared, resolution));
            throw new ResponseStatusException(result.failureStatus(), result.failureMessage());
        }
        ManualPaymentBankReconciliationCompletion completion = writeTransaction(() -> {
            ManualPaymentBankReconciliationResult result = finishManualPaymentBankReconciliation(prepared, resolution);
            if (!result.reconciled()) {
                return ManualPaymentBankReconciliationCompletion.failed(result);
            }
            return ManualPaymentBankReconciliationCompletion.completed(markPaidWithAttributionsLocked(invoiceId, request, principal));
        });
        if (!completion.reconciliation().reconciled()) {
            throw new ResponseStatusException(completion.reconciliation().failureStatus(), completion.reconciliation().failureMessage());
        }
        return completion.details();
    }

    private com.hunt.otziv.payments.api.UnpaidBankObservation.Source currentTochkaReceiptSource(long id, CommonManualPaymentAttributionRequest request) {
        var invoice = lockedInvoice(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) return null;
        var refs = paymentRefRepository.findByInvoiceIdForUpdate(id);
        if (refs.stream().noneMatch(ref -> PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref))
                && PAYMENT_REF_CURRENT.equals(paymentRefStatus(ref)))) return null;
        requireManualRecipientsAllowed(invoice,request);
        ownerReceipts.requireMatchingReport(id, request);
        requireNoIntegratedPaperDocument(invoice);
        var ref = paymentRoutes.boundUnpaidBankRef(invoice,refs);
        if (ref == null || !PROVIDER_TOCHKA.equals(normalizedPaymentProvider(ref)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Банковская операция общего счёта требует сверки");
        return paymentRoutes.bankSnapshot(invoice,ref,paymentRoutes.bankRecipientRouteVersion(invoice));
    }

    private boolean badReviewReceipt(CommonInvoice invoice) {
        return invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && (attentionError(invoice).startsWith(CommonInvoiceMembershipWorkflow.BAD_REVIEW_SUPPLEMENT_REQUIRED)
                    || (invoice.getBadReviewBankCheckAt() != null && invoice.getBadReviewBankRefId() != null
                        && CommonInvoiceBadReviewBankRepricing.WAIT.equals(invoice.getLastError())));
    }

    private void verifyBadReviewReceipt(CommonInvoice invoice, List<CommonInvoiceOrder> items,
            CommonManualPaymentAttributionRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.paymentReceived()) || CommonOwnerReceiptApprovals.includesOwner(request)
                || invoice.getSentAt() == null || invoice.getPaidKopecks() != 0 || invoice.getClientReportedAt() != null
                || invoice.getManualConfirmedAt() != null || invoice.getPaperInvoiceIssuedAt() != null
                || invoice.isPaperInvoiceAutomationHold() || invoice.isPaperInvoiceReviewHold() || items.isEmpty()
                || items.stream().anyMatch(item -> !item.isActiveMembership() || item.isPaid() || !item.isReady()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Проверьте состав счёта, документы и уже зарегистрированные поступления");
        long current = items.stream().mapToLong(item -> settlementService.amountKopecks(settlementService.payableSum(item.getOrder())))
                .reduce(0, Math::addExact);
        long receipt = request.attributions().stream().mapToLong(row -> row.amountKopecks()).reduce(0, Math::addExact);
        if (current <= 0 || current != receipt || invoice.getAmountKopecks() != receipt)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма перевода должна совпадать с выставленным счётом и текущей стоимостью заказов");
    }

    CommonInvoiceDetailsResponse markPaidWithAttributionsLocked(Long invoiceId, CommonManualPaymentAttributionRequest submitted, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        // Under the invoice lock: a repeat that raced with the first request now sees the time it recorded.
        CommonManualPaymentAttributionRequest request = withServerReceiptTime(invoiceId, submitted);
        CommonInvoice invoice = paymentPrelude.invoice();
        requireNoIntegratedPaperDocument(invoice);
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            if (commonManualPaymentAttributionCoordinator.replayIfRecorded(invoice, items, request, principal)) {
                return invoiceAfterOrderPrelude(invoiceId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже оплачен другой операцией");
        }
        if (badReviewReceipt(invoice)) {
            verifyBadReviewReceipt(invoice, items, request);
            invoice.setStatus(CommonInvoiceStatus.UNPAID);
            invoice.setLastError(null);
        }
        ensureCommonInvoiceNotNeedsAttention(invoice);
        if (invoice.getStatus() == CommonInvoiceStatus.DISABLED || invoice.getStatus() == CommonInvoiceStatus.BAN) {
            ensureCommonInvoiceCanBeMarkedPaid(invoice);
        }
        refreshInvoiceAmounts(invoice, items);
        ensureCommonInvoiceNotNeedsAttention(invoice);
        promoteCollectingInvoiceToReadyIfPossible(invoice, items);
        ensureCommonInvoiceCanBeMarkedPaid(invoice);
        requireManualRecipientsAllowed(invoice, request);
        acceptOwnerReport(invoice, items, request, principal);
        long manualAmountKopecks = remainingKopecks(invoice);
        ensureNoCurrentCommonTbankPaymentForManualCard(invoice);
        ensureCommonPaymentRefsSafeForManualCard(paymentRefRepository.findByInvoiceIdForUpdate(invoiceId));
        applyManualPaymentEvidence(invoice, items, new ManualPaymentConfirmationRequest(request.reason(), ""), principal);
        archiveAndClearCurrentPaymentRef(invoice, "manual_paid_actual_recipient");
        markTypedManualPaymentItems(items, CommonManualPaymentAttributionCoordinator.evidenceReference(invoice.getId(), request.idempotencyKey()));
        invoice.setBadReviewNoticePending(false);
        invoice.setBadReviewBankRefId(null);
        invoice.setBadReviewBankOrderId(null);
        invoice.setBadReviewBankTaskId(null);
        invoice.setBadReviewBankCheckAt(null);
        closePaidInvoiceWithFinalAttribution(invoice, items, () -> commonManualPaymentAttributionCoordinator.recordFinalReceipt(invoice, items, manualAmountKopecks, request, principal));
        return invoiceAfterOrderPrelude(invoiceId);
    }

    private boolean stageOwnerReport(Long invoiceId, CommonManualPaymentAttributionRequest submitted, boolean attention, Principal principal) {
        if (!CommonOwnerReceiptApprovals.includesOwner(submitted)) return false;
        return writeTransaction(() -> {
            CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
            // Under the invoice lock: the report keeps one receipt time however often the request is repeated.
            CommonManualPaymentAttributionRequest request = withServerReceiptTime(invoiceId, submitted);
            ensureCommonInvoiceVisibleForCurrentUser(invoice);
            if (invoice.getStatus() == CommonInvoiceStatus.PAID) return false;
            requireNoIntegratedPaperDocument(invoice);
            requireManualRecipientsAllowed(invoice, request);
            List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
            refreshInvoiceAmounts(invoice, items);
            if (attention) {
                if (!attentionError(invoice).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Платёжное состояние счёта изменилось");
            } else {
                promoteCollectingInvoiceToReadyIfPossible(invoice, items);
                ensureCommonInvoiceCanBeMarkedPaid(invoice);
            }
            boolean alreadyReported = ownerReceipts.pendingReport(invoice.getId()).isPresent();
            boolean staged = ownerReceipts.stage(invoice, items, request, attention, principal,
                    commonManualPaymentAttributionCoordinator.options(invoice, items, remainingKopecks(invoice), request,
                            ownerCardReceiptAllowed(invoice)));
            if (staged && !alreadyReported) {
                // A fresh report: the owner and administrators learn about it once, after this transaction commits.
                ownerReceipts.pendingReport(invoice.getId()).ifPresent(report ->
                        manualCardPaymentReviewNotificationService.notifyCommonInvoiceOwnerApprovalAfterCommit(
                                new ManualCardPaymentReviewNotificationService.CommonInvoiceOwnerApprovalRequest(
                                        report.id(), invoice.getId(), invoiceDisplayTitle(invoice),
                                        request.attributions().stream()
                                                .filter(row -> row != null && (row.recipientType() == com.hunt.otziv.contractor_payments.model.ContractorRecipientType.OWNER
                                                        || "OWNER".equals(row.recipientKey())))
                                                .mapToLong(row -> row.amountKopecks()).sum(),
                                        remainingKopecks(invoice), report.reportedBy(), normalize(request.reason()),
                                        firstOrderId(items))));
            }
            return staged;
        });
    }

    /**
     * The receipt time is the server's clock, not the clock of the device that marks the payment: a device in
     * another time zone shifted the receipt by hours, and one ahead of the server was refused as «в будущем».
     * The first request of an operation gets the current time; its repeat and the owner's decision on the
     * manager's report keep the time already stored for that operation, so they stay the same request.
     */
    CommonManualPaymentAttributionRequest withServerReceiptTime(Long invoiceId, CommonManualPaymentAttributionRequest submitted) {
        if (submitted == null || invoiceId == null || normalize(submitted.idempotencyKey()).isBlank()) {
            return submitted;
        }
        LocalDateTime receiptTime = commonManualPaymentAttributionCoordinator.recordedReceiptTime(invoiceId, submitted.idempotencyKey())
                .or(() -> ownerReceipts.reportedReceiptTime(invoiceId, submitted.idempotencyKey()))
                // Milliseconds, as clients sent before: the value survives the database and the stored report unchanged.
                .orElseGet(() -> LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        return submitted.withEffectiveAt(receiptTime);
    }

    /** Accepts the waiting owner report, if any, and clears the reminders that asked for the decision. */
    private void acceptOwnerReport(CommonInvoice invoice, List<CommonInvoiceOrder> items, CommonManualPaymentAttributionRequest request, Principal principal) {
        var pending = ownerReceipts.pendingReport(invoice.getId());
        ownerReceipts.accept(invoice, items, request, principal);
        pending.ifPresent(report -> manualCardPaymentReviewNotificationService.closeCommonInvoiceOwnerApprovalReminders(report.id()));
    }

    /** The owner did not receive the money: the report is closed and the manager who sent it is told. */
    public void rejectOwnerReport(long invoiceId, long reportId, Principal principal) {
        writeTransaction(() -> {
            CommonInvoice locked = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            ensureCommonInvoiceVisibleForCurrentUser(locked);
            var pending = ownerReceipts.pendingReport(invoiceId).filter(report -> report.id() == reportId);
            ownerReceipts.reject(invoiceId, reportId, principal);
            manualCardPaymentReviewNotificationService.closeCommonInvoiceOwnerApprovalReminders(reportId);
            pending.ifPresent(report -> {
                CommonInvoice invoice = invoiceRepository.findByIdWithAccount(invoiceId).orElse(null);
                manualCardPaymentReviewNotificationService.notifyCommonInvoiceOwnerRejectionAfterCommit(
                        new ManualCardPaymentReviewNotificationService.CommonInvoiceOwnerRejection(
                                reportId, invoiceId, invoice == null ? "" : invoiceDisplayTitle(invoice), report.reportedBy(),
                                principal == null ? "" : normalize(principal.getName()),
                                firstOrderId(invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId))));
            });
            return null;
        });
    }

    private String invoiceDisplayTitle(CommonInvoice invoice) {
        String title = normalize(invoice.getTitle());
        return title.isBlank() ? normalize(invoice.getAccount() == null ? null : invoice.getAccount().getName()) : title;
    }

    private Long firstOrderId(List<CommonInvoiceOrder> items) {
        return items == null ? null : items.stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull)
                .map(Order::getId).filter(Objects::nonNull).sorted().findFirst().orElse(null);
    }

    PreparedManualPaymentBankReconciliation prepareManualPaymentBankReconciliation(Long invoiceId) {
        return prepareManualPaymentBankReconciliation(invoiceId, null);
    }

    PreparedManualPaymentBankReconciliation prepareManualPaymentBankReconciliation(Long invoiceId, CommonManualPaymentAttributionRequest request) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        requireNoIntegratedPaperDocument(invoice);
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            return PreparedManualPaymentBankReconciliation.completed(invoiceId);
        }
        if (request != null) {
            requireManualRecipientsAllowed(invoice, request);
            ownerReceipts.requireMatchingReport(invoice.getId(), request);
        }
        boolean taskHold = badReviewReceipt(invoice) && request != null;
        if (taskHold) verifyBadReviewReceipt(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId), request);
        CommonInvoiceStatus originalStatus = taskHold ? CommonInvoiceStatus.UNPAID : manualPaymentReconciliationOriginalStatus(invoice);
        if (!MARK_PAID_STATUSES.contains(originalStatus)) {
            ensureCommonInvoiceNotNeedsAttention(invoice);
            ensureCommonInvoiceCanBeMarkedPaid(invoice);
        }
        if (!hasCurrentTbankPaymentBinding(invoice)) {
            ensureNoCurrentCommonTbankPaymentForManualCard(invoice);
            ensureCommonPaymentRefsSafeForManualCard(paymentRefRepository.findByInvoiceIdForUpdate(invoiceId));
            if (!taskHold && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION) {
                invoice.setStatus(originalStatus);
                invoice.setLastError(null);
                invoiceRepository.save(invoice);
            }
            return PreparedManualPaymentBankReconciliation.completed(invoiceId);
        }
        CommonInvoicePaymentRef paymentRef = currentPaymentRefForPaperModeSwitch(invoice);
        String refStatus = paymentRefStatus(paymentRef);
        if (!isPaperModeSwitchSafeTerminalStatus(refStatus) && !isPaperModeSwitchFinancialStatus(refStatus)) {
            ensureTbankCancellationSupported(paymentRef, "Ручной перевод не зачислен");
        }
        if (!isPaperModeSwitchSafeTerminalStatus(refStatus) && !isPaperModeSwitchFinancialStatus(refStatus) && !canCancelInitializedPaymentRef(paymentRef)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Банковская сессия заполнена не полностью. Оплата по прежним реквизитам не учтена; нужна сверка T-Bank");
        }
        String knownStatus = isPaperModeSwitchSafeTerminalStatus(refStatus) || isPaperModeSwitchFinancialStatus(refStatus) ? refStatus : "";
        if (knownStatus.isBlank()) {
            // Marked before GetState/Cancel so a concurrent webhook sees the cancellation lifecycle.
            paymentRef.setStatus(PAYMENT_REF_CANCELING);
            paymentRef.setCancelAttempts(cancelAttempts(paymentRef) + 1);
            paymentRef.setReason(limit("manual_payment_tbank_reconciliation", 160));
            paymentRefRepository.save(paymentRef);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setLastError(limit(MANUAL_PAYMENT_TBANK_RECONCILIATION_IN_PROGRESS + originalStatus.name(), 512));
            invoiceRepository.save(invoice);
        }
        return new PreparedManualPaymentBankReconciliation(invoiceId, originalStatus, paymentRef.getId(), normalize(paymentRef.getTbankOrderId()), normalize(paymentRef.getTbankPaymentId()), normalize(paymentRef.getTbankTerminalKey()), paymentRef.getAmountKopecks(), knownStatus);
    }

    void ensureTbankCancellationSupported(CommonInvoicePaymentRef paymentRef, String operationFailure) {
        String provider = normalizedPaymentProvider(paymentRef);
        if (PROVIDER_TBANK.equals(provider)) {
            return;
        }
        if (PROVIDER_TOCHKA.equals(provider)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Активную платёжную попытку Точки нельзя автоматически проверить или отменить через T-Bank. " + normalize(operationFailure) + "; сначала завершите сверку попытки Точки");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Провайдер активной банковской попытки не поддерживает автоматическую отмену. " + normalize(operationFailure) + "; нужна ручная сверка");
    }

    CommonInvoiceStatus manualPaymentReconciliationOriginalStatus(CommonInvoice invoice) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            return invoice == null || invoice.getStatus() == null ? CommonInvoiceStatus.READY : invoice.getStatus();
        }
        CommonInvoiceStatus controlledOriginal = controlledManualPaymentOriginalStatus(invoice);
        if (controlledOriginal != null) {
            return controlledOriginal;
        }
        ensureCommonInvoiceNotNeedsAttention(invoice);
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет требует отдельной сверки");
    }

    CommonInvoiceStatus controlledManualPaymentOriginalStatus(CommonInvoice invoice) {
        return settlementService.controlledManualPaymentOriginalStatus(invoice);
    }

    PaperInvoiceBankResolution resolveManualPaymentBankSession(PreparedManualPaymentBankReconciliation prepared) {
        PaymentProfile profile = paymentProfileService.findByTerminalKey(prepared.terminalKey()).orElse(null);
        if (profile == null) {
            return manualPaymentBankAmbiguous(HttpStatus.CONFLICT, "Не найден платёжный профиль T-Bank. Оплата по прежним реквизитам пока не учтена");
        }
        TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile, prepared.terminalKey());
        if (!runtimeProfile.hasCredentials()) {
            return manualPaymentBankAmbiguous(HttpStatus.CONFLICT, "Недоступны реквизиты T-Bank для проверки ссылки. Оплата по прежним реквизитам пока не учтена");
        }
        TbankGetStateResponse state;
        try {
            state = tbankClient.getState(runtimeProfile, prepared.paymentId());
        } catch (RuntimeException failure) {
            log.warn("Не удалось сверить T-Bank перед ручным подтверждением общего счёта: invoiceId={}, paymentId={}", prepared.invoiceId(), maskPaymentId(prepared.paymentId()), failure);
            return manualPaymentBankAmbiguous(HttpStatus.BAD_GATEWAY, "T-Bank не подтвердил состояние ссылки. Ничего не зачислено; повторите позже");
        }
        String mismatch = manualPaymentBankStateMismatch(prepared, state, runtimeProfile);
        if (mismatch != null) {
            return manualPaymentBankAmbiguous(HttpStatus.CONFLICT, mismatch);
        }
        String providerStatus = normalize(state.status()).toUpperCase(Locale.ROOT);
        if (isPaperModeSwitchSafeTerminalStatus(providerStatus)) {
            return PaperInvoiceBankResolution.safe(providerStatus);
        }
        if (isPaperModeSwitchFinancialStatus(providerStatus)) {
            return manualPaymentBankPaymentDetected(providerStatus);
        }
        if (!"NEW".equals(providerStatus) && !"FORM_SHOWED".equals(providerStatus)) {
            return manualPaymentBankAmbiguous(HttpStatus.CONFLICT, "T-Bank вернул неоднозначный статус " + readableProviderStatus(providerStatus) + ". Ничего не зачислено; нужна сверка");
        }
        // The client must not be able to pay the old form after the transfer is credited.
        try {
            TbankCancelResponse response = tbankClient.cancel(runtimeProfile, new TbankCancelCommand(prepared.paymentId(), prepared.amountKopecks()));
            String cancelMismatch = manualPaymentBankCancelMismatch(prepared, response, runtimeProfile);
            if (cancelMismatch != null) {
                return manualPaymentBankAmbiguous(HttpStatus.CONFLICT, cancelMismatch);
            }
            String cancelStatus = normalize(response.status()).toUpperCase(Locale.ROOT);
            if (PAYMENT_REF_CANCELED.equals(cancelStatus)) {
                return PaperInvoiceBankResolution.safe(cancelStatus);
            }
            if (isPaperModeSwitchFinancialStatus(cancelStatus)) {
                return manualPaymentBankPaymentDetected(cancelStatus);
            }
            return manualPaymentBankAmbiguous(HttpStatus.CONFLICT, "T-Bank не подтвердил отмену ссылки: статус " + readableProviderStatus(cancelStatus) + ". Ничего не зачислено");
        } catch (RuntimeException failure) {
            log.warn("Исход отмены T-Bank перед ручным подтверждением неоднозначен: invoiceId={}, paymentId={}", prepared.invoiceId(), maskPaymentId(prepared.paymentId()), failure);
            return manualPaymentBankAmbiguous(HttpStatus.BAD_GATEWAY, "T-Bank не подтвердил отмену ссылки. Счёт оставлен на безопасной сверке; повторите позже");
        }
    }

    String manualPaymentBankCancelMismatch(PreparedManualPaymentBankReconciliation prepared, TbankCancelResponse response, TbankPaymentProfile runtimeProfile) {
        if (response == null || !response.success()) {
            return "T-Bank не подтвердил отмену ссылки. Ничего не зачислено";
        }
        if (!normalize(response.paymentId()).isBlank() && !prepared.paymentId().equals(normalize(response.paymentId()))) {
            return "PaymentId отмены T-Bank не совпал с общим счётом. Ничего не зачислено";
        }
        if (!normalize(response.orderId()).isBlank() && !prepared.tbankOrderId().isBlank() && !prepared.tbankOrderId().equals(normalize(response.orderId()))) {
            return "OrderId отмены T-Bank не совпал с общим счётом. Ничего не зачислено";
        }
        if (!normalize(response.terminalKey()).isBlank() && !normalize(runtimeProfile.terminalKey()).equals(normalize(response.terminalKey()))) {
            return "TerminalKey отмены T-Bank не совпал с общим счётом. Ничего не зачислено";
        }
        if (response.amount() != null && response.amount() != prepared.amountKopecks()) {
            return "Сумма отмены T-Bank не совпала с общим счётом. Ничего не зачислено";
        }
        return null;
    }

    PaperInvoiceBankResolution manualPaymentBankPaymentDetected(String providerStatus) {
        String cleanStatus = readableProviderStatus(providerStatus);
        return new PaperInvoiceBankResolution(false, cleanStatus, cleanStatus, HttpStatus.CONFLICT, "T-Bank сообщил статус " + cleanStatus + ": по ссылке есть платёжное движение. Ручной перевод не зачислен; нужна сверка двух оплат");
    }

    PaperInvoiceBankResolution manualPaymentBankAmbiguous(HttpStatus status, String message) {
        return new PaperInvoiceBankResolution(false, "UNKNOWN", PAYMENT_REF_CANCEL_FAILED, status == null ? HttpStatus.CONFLICT : status, message);
    }

    String manualPaymentBankStateMismatch(PreparedManualPaymentBankReconciliation prepared, TbankGetStateResponse state, TbankPaymentProfile runtimeProfile) {
        if (state == null || !state.success() || !"0".equals(state.errorCode())) {
            return "T-Bank не подтвердил состояние ссылки. Ничего не зачислено";
        }
        if (!prepared.paymentId().equals(normalize(state.paymentId()))) {
            return "PaymentId ответа T-Bank не совпал с общим счётом. Ничего не зачислено";
        }
        if (prepared.tbankOrderId().isBlank() || !prepared.tbankOrderId().equals(normalize(state.orderId()))) {
            return "OrderId ответа T-Bank не совпал с общим счётом. Ничего не зачислено";
        }
        if (!normalize(runtimeProfile.terminalKey()).equals(normalize(state.terminalKey()))) {
            return "TerminalKey ответа T-Bank не совпал с общим счётом. Ничего не зачислено";
        }
        if (!Objects.equals(state.amount(),prepared.amountKopecks())
                && !(isPaperModeSwitchSafeTerminalStatus(state.status()) && Objects.equals(state.amount(),0L))) {
            return "Сумма ответа T-Bank не совпала с общим счётом. Ничего не зачислено";
        }
        return null;
    }

    ManualPaymentBankReconciliationResult finishManualPaymentBankReconciliation(PreparedManualPaymentBankReconciliation prepared, PaperInvoiceBankResolution resolution) {
        CommonInvoice invoice = lockedInvoice(prepared.invoiceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        CommonInvoicePaymentRef paymentRef = paymentRefRepository.findByIdForUpdate(prepared.providerPaymentRefId()).orElse(null);
        PaperInvoiceBankResolution effectiveResolution = manualPaymentBankResolutionAfterConcurrentWebhook(paymentRef, resolution);
        if (!manualPaymentBankBindingUnchanged(invoice, paymentRef, prepared, effectiveResolution)) {
            if (isControlledManualPaymentReconciliation(invoice, prepared.originalStatus())) {
                String concurrentProviderStatus = paymentRefStatus(paymentRef);
                String prefix = isPaperModeSwitchFinancialStatus(concurrentProviderStatus) ? MANUAL_PAYMENT_TBANK_PAYMENT_DETECTED : MANUAL_PAYMENT_TBANK_RECONCILIATION_RETRY;
                invoice.setLastError(limit(prefix + prepared.originalStatus().name() + ": состояние счёта изменилось во время сверки" + (concurrentProviderStatus.isBlank() ? "" : "; T-Bank=" + readableProviderStatus(concurrentProviderStatus)), 512));
                invoiceRepository.save(invoice);
            }
            return ManualPaymentBankReconciliationResult.failed(HttpStatus.CONFLICT, "Состояние общего счёта изменилось во время сверки. Ничего не зачислено; обновите карточку");
        }
        if (!effectiveResolution.switchAllowed()) {
            paymentRef.setStatus(limit(effectiveResolution.durableRefStatus(), 32));
            paymentRef.setReason(limit("manual_payment_bank_reconciliation_blocked:" + effectiveResolution.providerStatus(), 160));
            paymentRefRepository.save(paymentRef);
            invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
            invoice.setNextReminderAt(null);
            invoice.setPaymentUrl(null);
            String prefix = isPaperModeSwitchFinancialStatus(effectiveResolution.providerStatus()) ? MANUAL_PAYMENT_TBANK_PAYMENT_DETECTED : MANUAL_PAYMENT_TBANK_RECONCILIATION_RETRY;
            invoice.setLastError(limit(prefix + prepared.originalStatus().name() + ": " + effectiveResolution.failureMessage(), 512));
            invoiceRepository.save(invoice);
            return ManualPaymentBankReconciliationResult.failed(effectiveResolution.failureStatus(), effectiveResolution.failureMessage());
        }
        paymentRef.setStatus("DEADLINE_EXPIRED".equals(effectiveResolution.durableRefStatus()) ? "EXPIRED" : limit(effectiveResolution.durableRefStatus(), 32));
        paymentRef.setProviderStatus(effectiveResolution.providerStatus());
        paymentRef.setObserveReplacedBank(PAYMENT_REF_ARCHIVED.equals(effectiveResolution.durableRefStatus()));
        paymentRef.setReason(PAYMENT_REF_ARCHIVED.equals(effectiveResolution.durableRefStatus())
                ? "actual_recipient_received_observe_only" : "manual_payment_bank_session_closed");
        paymentRefRepository.save(paymentRef);
        clearCurrentPaymentRef(invoice);
        invoice.setStatus(prepared.originalStatus());
        invoice.setNextReminderAt(null);
        invoice.setLastError(null);
        invoiceRepository.save(invoice);
        return ManualPaymentBankReconciliationResult.reconciledSuccessfully();
    }

    PaperInvoiceBankResolution manualPaymentBankResolutionAfterConcurrentWebhook(CommonInvoicePaymentRef paymentRef, PaperInvoiceBankResolution requested) {
        String concurrentStatus = paymentRefStatus(paymentRef);
        if (isPaperModeSwitchFinancialStatus(concurrentStatus)) {
            return PaperInvoiceBankResolution.blockedByPayment(concurrentStatus);
        }
        if (isPaperModeSwitchSafeTerminalStatus(concurrentStatus)) {
            return PaperInvoiceBankResolution.safe(concurrentStatus);
        }
        return requested;
    }

    boolean manualPaymentBankBindingUnchanged(CommonInvoice invoice, CommonInvoicePaymentRef paymentRef, PreparedManualPaymentBankReconciliation prepared, PaperInvoiceBankResolution resolution) {
        boolean controlledAttention = isControlledManualPaymentReconciliation(invoice, prepared.originalStatus());
        boolean expectedControlState = normalize(prepared.knownProviderStatus()).isBlank() ? controlledAttention : invoice != null && (invoice.getStatus() == prepared.originalStatus() || controlledAttention);
        String currentPaymentRefStatus = paymentRefStatus(paymentRef);
        String knownProviderStatus = normalize(prepared.knownProviderStatus()).toUpperCase(Locale.ROOT);
        String resolvedProviderStatus = normalize(resolution == null ? null : resolution.providerStatus()).toUpperCase(Locale.ROOT);
        boolean expectedPaymentRefState = knownProviderStatus.isBlank() ? Set.of("VERIFYING_RECEIPT", PAYMENT_REF_CANCELING).contains(currentPaymentRefStatus) || (!resolvedProviderStatus.isBlank() && resolvedProviderStatus.equals(currentPaymentRefStatus) && (isPaperModeSwitchSafeTerminalStatus(currentPaymentRefStatus) || isPaperModeSwitchFinancialStatus(currentPaymentRefStatus))) : knownProviderStatus.equals(currentPaymentRefStatus);
        boolean invoiceBindingUnchanged = prepared.tbankOrderId().equals(normalize(invoice == null ? null : invoice.getTbankOrderId())) && prepared.paymentId().equals(normalize(invoice == null ? null : invoice.getTbankPaymentId())) && prepared.terminalKey().equals(normalize(invoice == null ? null : invoice.getTbankTerminalKey())) && Objects.equals(prepared.amountKopecks(), invoice == null ? null : invoice.getTbankPaymentAmountKopecks());
        boolean concurrentTerminalArchivedBinding = !resolvedProviderStatus.isBlank() && resolvedProviderStatus.equals(currentPaymentRefStatus) && (isPaperModeSwitchSafeTerminalStatus(currentPaymentRefStatus) || isPaperModeSwitchFinancialStatus(currentPaymentRefStatus)) && invoice != null && normalize(invoice.getTbankOrderId()).isBlank() && normalize(invoice.getTbankPaymentId()).isBlank() && normalize(invoice.getTbankTerminalKey()).isBlank() && invoice.getTbankPaymentAmountKopecks() == null;
        return invoice != null && paymentRef != null && expectedControlState && expectedPaymentRefState && invoice.getPaidKopecks() == 0 && invoice.getClientReportedAt() == null && invoice.getManualConfirmedAt() == null && (invoiceBindingUnchanged || concurrentTerminalArchivedBinding) && prepared.tbankOrderId().equals(normalize(paymentRef.getTbankOrderId())) && prepared.paymentId().equals(normalize(paymentRef.getTbankPaymentId())) && prepared.terminalKey().equals(normalize(paymentRef.getTbankTerminalKey())) && Objects.equals(prepared.amountKopecks(), paymentRef.getAmountKopecks());
    }

    boolean isControlledManualPaymentReconciliation(CommonInvoice invoice, CommonInvoiceStatus originalStatus) {
        if (invoice == null || originalStatus == null || invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            return false;
        }
        String reconciliationState = normalize(invoice.getLastError());
        return reconciliationState.startsWith(MANUAL_PAYMENT_TBANK_RECONCILIATION_IN_PROGRESS + originalStatus.name()) || reconciliationState.startsWith(MANUAL_PAYMENT_TBANK_RECONCILIATION_RETRY + originalStatus.name()) || reconciliationState.startsWith(MANUAL_PAYMENT_TBANK_PAYMENT_DETECTED + originalStatus.name());
    }

    @Transactional
    public CommonManualPaymentOptionsResponse manualPaymentOptions(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        refreshInvoiceAmounts(invoice, items);
        return ownerReceipts.attach(commonManualPaymentAttributionCoordinator.options(invoice, items, remainingKopecks(invoice),
                null, ownerCardReceiptAllowed(invoice)));
    }

    @Transactional
    public CommonManualPaymentOtherRecipientsResponse manualPaymentOtherRecipients(Long invoiceId) {
        CommonInvoice invoice = lockedInvoice(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        return commonManualPaymentAttributionCoordinator.otherRecipients(invoice, invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse reportPaidByManualCardTransfer(Long invoiceId, CommonInvoiceManualCardPaymentRequest request, Principal principal) {
        actualPaymentAttributionFlowPolicy.requireLegacyFlow();
        String reason = validateCommonInvoiceManualCardPaymentReason(request);
        CommonInvoice snapshot = invoiceRepository.findByIdWithAccount(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(snapshot);
        if (!attentionError(snapshot).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручное зачисление из этой карточки доступно только для конфликта одиночных платежей");
        }
        List<Long> orderIds = invoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId).stream().filter(Objects::nonNull).distinct().toList();
        reconcileStandaloneBankRoutesBeforeCommonManualPayment(orderIds);
        return writeTransaction(() -> reportPaidByManualCardTransferLocked(invoiceId, reason, principal));
    }

    CommonInvoiceDetailsResponse reportPaidByManualCardTransferLocked(Long invoiceId, String reason, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        CommonInvoice invoice = paymentPrelude.invoice();
        actualPaymentAttributionFlowPolicy.requireLegacyFlowLocked();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureGenericConfirmationDoesNotUseContractorSource(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!attentionError(invoice).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежное состояние общего счета изменилось; обновите карточку");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (items.isEmpty() || !allOrdersReady(items)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручную оплату можно зачислить только после готовности всех заказов общего счета");
        }
        ensureNoCurrentCommonTbankPaymentForManualCard(invoice);
        ensureCommonPaymentRefsSafeForManualCard(paymentRefRepository.findByInvoiceIdForUpdate(invoiceId));
        Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentPrelude.paymentLinksByOrder());
        List<Long> closedRouteIds = closeStandaloneRoutesForCommonManualPaymentOrThrow(paymentLinksRequiringCommonInvoiceRouteCheck(paymentPrelude.paymentLinksByOrder(), items, appliedStandalonePayments), invoiceId, reason, principal);
        refreshInvoiceAmounts(invoice, items);
        long manualAmountKopecks = remainingKopecks(invoice);
        if (manualAmountKopecks > 0) {
            ManualPaymentConfirmationRequest evidence = new ManualPaymentConfirmationRequest(reason, "");
            applyManualPaymentEvidence(invoice, items, evidence, principal);
        }
        closePaidInvoice(invoice, items);
        if (manualAmountKopecks > 0) {
            String actor = principal == null ? "" : normalize(principal.getName());
            manualCardPaymentReviewNotificationService.notifyCommonInvoiceAfterCommit(new ManualCardPaymentReviewNotificationService.CommonInvoiceReviewRequest(invoice.getId(), normalize(invoice.getTitle()).isBlank() ? normalize(invoice.getAccount() == null ? null : invoice.getAccount().getName()) : normalize(invoice.getTitle()), manualAmountKopecks, actor, reason, items.stream().map(CommonInvoiceOrder::getOrder).filter(Objects::nonNull).map(Order::getId).filter(Objects::nonNull).toList(), closedRouteIds));
        }
        return invoiceAfterOrderPrelude(invoiceId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CommonInvoiceDetailsResponse reportPaidByManualCardTransferWithAttributions(Long invoiceId, CommonManualPaymentAttributionRequest submitted, Principal principal) {
        CommonManualPaymentAttributionRequest request = withServerReceiptTime(invoiceId, submitted);
        String reason = validateCommonManualPaymentAttributionRequest(request);
        if (stageOwnerReport(invoiceId, request, true, principal)) return writeTransaction(() -> invoiceAfterOrderPrelude(invoiceId));
        CommonInvoice snapshot = invoiceRepository.findByIdWithAccount(invoiceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        ensureCommonInvoiceVisibleForCurrentUser(snapshot);
        if (snapshot.getStatus() == CommonInvoiceStatus.PAID) {
            return writeTransaction(() -> replayPaidManualAttributionLocked(invoiceId, request, principal));
        }
        if (!attentionError(snapshot).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручное зачисление из этой карточки доступно только для конфликта одиночных платежей");
        }
        requireManualRecipientsAllowed(snapshot, request);
        ownerReceipts.requireMatchingReport(invoiceId, request);
        List<Long> orderIds = invoiceOrderRepository.findOrderIdsByInvoiceId(invoiceId).stream().filter(Objects::nonNull).distinct().toList();
        reconcileStandaloneBankRoutesBeforeCommonManualPayment(orderIds);
        return writeTransaction(() -> reportPaidByManualCardTransferWithAttributionsLocked(invoiceId, request, reason, principal));
    }

    CommonInvoiceDetailsResponse replayPaidManualAttributionLocked(Long invoiceId, CommonManualPaymentAttributionRequest submitted, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        // Under the invoice lock: a repeat that raced with the first request now sees the time it recorded.
        CommonManualPaymentAttributionRequest request = withServerReceiptTime(invoiceId, submitted);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (invoice.getStatus() != CommonInvoiceStatus.PAID || !commonManualPaymentAttributionCoordinator.replayIfRecorded(invoice, items, request, principal)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже изменен другой операцией");
        }
        return invoiceAfterOrderPrelude(invoiceId);
    }

    CommonInvoiceDetailsResponse reportPaidByManualCardTransferWithAttributionsLocked(Long invoiceId, CommonManualPaymentAttributionRequest submitted, String reason, Principal principal) {
        LockedInvoicePaymentPrelude paymentPrelude = lockedInvoiceAfterStandalonePaymentPrelude(invoiceId);
        // Under the invoice lock: a repeat that raced with the first request now sees the time it recorded.
        CommonManualPaymentAttributionRequest request = withServerReceiptTime(invoiceId, submitted);
        CommonInvoice invoice = paymentPrelude.invoice();
        ensureCommonInvoiceVisibleForCurrentUser(invoice);
        ensureCommonInvoiceNeedsAttention(invoice);
        if (!attentionError(invoice).startsWith(STANDALONE_PAYMENT_ROUTE_CONFLICT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежное состояние общего счета изменилось; обновите карточку");
        }
        List<CommonInvoiceOrder> items = invoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId);
        if (items.isEmpty() || !allOrdersReady(items)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ручную оплату можно зачислить только после готовности всех заказов общего счета");
        }
        ensureNoCurrentCommonTbankPaymentForManualCard(invoice);
        ensureCommonPaymentRefsSafeForManualCard(paymentRefRepository.findByInvoiceIdForUpdate(invoiceId));
        Set<PaymentLink> appliedStandalonePayments = synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentPrelude.paymentLinksByOrder());
        closeStandaloneRoutesForCommonManualPaymentOrThrow(paymentLinksRequiringCommonInvoiceRouteCheck(paymentPrelude.paymentLinksByOrder(), items, appliedStandalonePayments), invoiceId, reason, principal);
        refreshInvoiceAmounts(invoice, items);
        requireManualRecipientsAllowed(invoice, request);
        acceptOwnerReport(invoice, items, request, principal);
        long manualAmountKopecks = remainingKopecks(invoice);
        applyManualPaymentEvidence(invoice, items, new ManualPaymentConfirmationRequest(reason, ""), principal);
        markTypedManualPaymentItems(items, CommonManualPaymentAttributionCoordinator.evidenceReference(invoice.getId(), request.idempotencyKey()));
        closePaidInvoiceWithFinalAttribution(invoice, items, () -> commonManualPaymentAttributionCoordinator.recordFinalReceipt(invoice, items, manualAmountKopecks, request, principal));
        return invoiceAfterOrderPrelude(invoiceId);
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

    Optional<CommonInvoice> lockedInvoiceAfterOrderPrelude(Long invoiceId) {
        return settlementService.lockedInvoiceAfterOrderPrelude(invoiceId);
    }

    Map<Long, List<PaymentLink>> paymentLinksRequiringCommonInvoiceRouteCheck(Map<Long, List<PaymentLink>> paymentLinksByOrder, Collection<CommonInvoiceOrder> items, Set<PaymentLink> appliedStandalonePayments) {
        return settlementService.paymentLinksRequiringCommonInvoiceRouteCheck(paymentLinksByOrder, items, appliedStandalonePayments);
    }

    void reconcileStandaloneBankRoutesBeforeCommonManualPayment(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdInForRead(orderIds);
        LocalDateTime now = LocalDateTime.now();
        links.stream().filter(this::isBankPayment).filter(link -> link.getId() != null).filter(link -> !normalize(link.getTbankPaymentId()).isBlank()).forEach(link -> standalonePaymentOperations.reconcileBankLink(link.getId(), now));
        paymentLinkRepository.findByOrderIdInForRead(orderIds).stream().filter(this::isBankPayment).filter(link -> link.getId() != null).filter(link -> link.getStatus() == PaymentLinkStatus.INITIATED).filter(link -> {
            String providerStatus = normalize(link.getProviderTerminalStatus()).toUpperCase(Locale.ROOT);
            return "NEW".equals(providerStatus) || "FORM_SHOWED".equals(providerStatus);
        }).forEach(link -> standalonePaymentOperations.cancel(link.getId()));
    }

    boolean isBankPayment(PaymentLink link) {
        return link != null && (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR);
    }

    void ensureNoCompetingStandaloneRoutesOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        settlementService.ensureNoCompetingStandaloneRoutesOrThrow(paymentLinksByOrder);
    }

    List<Long> closeStandaloneRoutesForCommonManualPaymentOrThrow(Map<Long, List<PaymentLink>> paymentLinksByOrder, Long invoiceId, String reason, Principal principal) {
        List<PaymentLink> closable = new ArrayList<>();
        for (Map.Entry<Long, List<PaymentLink>> entry : (paymentLinksByOrder == null ? Map.<Long, List<PaymentLink>>of() : paymentLinksByOrder).entrySet()) {
            for (PaymentLink link : entry.getValue()) {
                if (isSafelyClosedStandaloneRoute(link)) {
                    continue;
                }
                if (StandaloneBankPaymentPolicy.canAutoCloseForCommonInvoice(link) || canManagerCloseManualRouteForCommonPayment(link)) {
                    closable.add(link);
                    continue;
                }
                ensureNoCompetingStandaloneRoutesOrThrow(Map.of(entry.getKey(), List.of(link)));
            }
        }
        if (closable.isEmpty()) {
            return List.of();
        }
        String actor = principal == null ? "" : normalize(principal.getName());
        LocalDateTime now = LocalDateTime.now();
        for (PaymentLink link : closable) {
            PaymentLinkStatus previousStatus = link.getStatus();
            taskReceiptIntegrationService.release(link, "Отдельный маршрут отменен после ручной оплаты общего счета");
            link.setStatus(PaymentLinkStatus.CANCELED);
            link.setExpiresAt(link.getExpiresAt() == null || link.getExpiresAt().isAfter(now) ? now : link.getExpiresAt());
            link.setLastError(limit("common_invoice_manual_card_paid: invoice=" + invoiceId + "; actor=" + limit(actor.isBlank() ? "manager" : actor, 80) + "; previous_status=" + (previousStatus == null ? "UNKNOWN" : previousStatus.name()) + "; reason=" + reason, 512));
        }
        paymentLinkRepository.saveAll(closable);
        return closable.stream().map(PaymentLink::getId).filter(Objects::nonNull).toList();
    }

    boolean canManagerCloseManualRouteForCommonPayment(PaymentLink link) {
        if (!isManualPayment(link) || link.getManualSource() == ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE || !MANUAL_COMMON_PAYMENT_CLOSABLE_ROUTE_STATUSES.contains(link.getStatus())) {
            return false;
        }
        return normalize(link.getTbankPaymentId()).isBlank() && normalize(link.getTbankOrderId()).isBlank() && normalize(link.getTbankTerminalKey()).isBlank() && normalize(link.getBankInitNonce()).isBlank() && normalize(link.getBankCancelNonce()).isBlank() && link.getBankCancelOriginStatus() == null;
    }

    Set<PaymentLink> synchronizeConfirmedStandalonePaymentsOrThrow(CommonInvoice invoice, List<CommonInvoiceOrder> items, Map<Long, List<PaymentLink>> paymentLinksByOrder) {
        return settlementService.synchronizeConfirmedStandalonePaymentsOrThrow(invoice, items, paymentLinksByOrder);
    }

    boolean isManualPayment(PaymentLink link) {
        return settlementService.isManualPayment(link);
    }

    /**
     * By the bank link the owner is paid through the bank and the bank confirms it. A transfer that went past
     * the link to the owner's card may be recorded only where the bank profile of the invoice lets its owner
     * take card transfers at all; an owner who works through the bank only is never recorded a card transfer.
     */
    boolean ownerCardReceiptAllowed(CommonInvoice invoice) {
        return invoice != null && paymentProfileService.ownerCardAllowed(invoice.getPaymentRouteProfileId());
    }

    private void requireManualRecipientsAllowed(CommonInvoice invoice, CommonManualPaymentAttributionRequest request) {
        if (CommonOwnerReceiptApprovals.hasBankRoute(invoice) && !ownerCardReceiptAllowed(invoice)
                && CommonOwnerReceiptApprovals.includesOwner(request)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Поступление владельцу по банковской ссылке подтверждает банк");
        }
    }

    void ensureGenericConfirmationDoesNotUseContractorSource(CommonInvoice invoice) {
        if (CommonOwnerReceiptApprovals.hasBankRoute(invoice))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплату по банковской ссылке подтверждает банк. Для перевода сотруднику укажите получателя");
        if (isFrozenLiveContractorSource(invoice) || isFrozenManualTaskSource(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Поступление по зафиксированному получателю подтверждается только с указанием фактического получателя");
        }
    }

    boolean isFrozenManualTaskSource(CommonInvoice invoice) {
        return invoice != null && hasFrozenCommonPaymentRoute(invoice) && invoice.getPaymentRouteManualSource() == ManualPaymentSource.MANUAL_TASK;
    }

    boolean isFrozenLiveContractorSource(CommonInvoice invoice) {
        return settlementService.isFrozenLiveContractorSource(invoice);
    }

    boolean isSafelyClosedStandaloneRoute(PaymentLink link) {
        return settlementService.isSafelyClosedStandaloneRoute(link);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    void promoteCollectingInvoiceToReadyIfPossible(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.COLLECTING || !areInvoiceItemsReady(items)) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoiceRepository.save(invoice);
    }

    boolean areInvoiceItemsReady(List<CommonInvoiceOrder> items) {
        return settlementService.areInvoiceItemsReady(items);
    }

    void ensureCommonInvoiceNotNeedsAttention(CommonInvoice invoice) {
        invoiceDelivery.ensureCommonInvoiceNotNeedsAttention(invoice);
    }

    void ensureCommonInvoiceCanBeMarkedPaid(CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        if (!MARK_PAID_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Оплаченным можно отметить только уже выставленный общий счет");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет уже оплачен");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.DISABLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отключенный общий счет нельзя отметить оплаченным");
        }
        if (invoice.getStatus() == CommonInvoiceStatus.BAN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет в статусе Бан нельзя отметить оплаченным");
        }
    }

    void ensureCommonInvoiceNeedsAttention(CommonInvoice invoice) {
        if (invoice == null || invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Общий счет не находится в ручной проверке");
        }
    }

    String paymentRefStatus(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefStatus(ref);
    }

    String normalizedPaymentProvider(CommonInvoicePaymentRef ref) {
        return settlementService.normalizedPaymentProvider(ref);
    }

    String attentionError(CommonInvoice invoice) {
        return settlementService.attentionError(invoice);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.closePaidInvoice(invoice, items);
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds) {
        settlementService.closePaidInvoice(invoice, items, alreadyClosedOrderIds);
    }

    void markTypedManualPaymentItems(List<CommonInvoiceOrder> items, String evidenceReference) {
        String expected = normalize(evidenceReference);
        if (expected.isBlank() || expected.length() > 160) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось зафиксировать подтверждение фактического поступления");
        }
        for (CommonInvoiceOrder item : items) {
            if (item == null || item.isPaid()) {
                continue;
            }
            String recorded = normalize(item.getActualPaymentEvidenceReference());
            if (!recorded.isBlank() && !recorded.equals(expected)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Позиция общего счета уже связана с другим подтверждением поступления");
            }
            item.setActualPaymentEvidenceReference(expected);
        }
    }

    void closePaidInvoiceWithFinalAttribution(CommonInvoice invoice, List<CommonInvoiceOrder> items, Runnable finalAttribution) {
        closePaidInvoice(invoice, items, Set.of(), Objects.requireNonNull(finalAttribution));
    }

    void closePaidInvoice(CommonInvoice invoice, List<CommonInvoiceOrder> items, Set<Long> alreadyClosedOrderIds, Runnable finalAttribution) {
        settlementService.closePaidInvoice(invoice, items, alreadyClosedOrderIds, finalAttribution);
    }

    void refreshInvoiceAmounts(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        settlementService.refreshInvoiceAmounts(invoice, items);
    }

    CommonInvoiceDetailsResponse invoiceDetails(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoiceDetailsAssembler.invoiceDetails(invoice, items);
    }

    void ensureCommonInvoiceVisibleForCurrentUser(CommonInvoice invoice) {
        invoiceDelivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
    }

    boolean allOrdersReady(List<CommonInvoiceOrder> items) {
        return settlementService.allOrdersReady(items);
    }

    void applyManualPaymentEvidence(CommonInvoice invoice, List<CommonInvoiceOrder> items, ManualPaymentConfirmationRequest request, Principal principal) {
        validateManualPaymentEvidence(request);
        String actor = principal == null ? "" : normalize(principal.getName());
        LocalDateTime confirmedAt = LocalDateTime.now();
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_MANUAL);
        invoice.setManualPaidBy(actor);
        invoice.setManualPaymentComment(normalize(request.comment()));
        invoice.setManualPaymentReceiptUrl(normalize(request.receiptUrl()));
        invoice.setManualConfirmedAt(confirmedAt);
        for (CommonInvoiceOrder item : items) {
            if (!item.isPaid()) {
                applyManualPaymentEvidence(item, request, actor);
            }
        }
        invoiceOrderRepository.saveAll(items);
        invoiceRepository.save(invoice);
    }

    void applyManualPaymentEvidence(CommonInvoice invoice, CommonInvoiceOrder item, ManualPaymentConfirmationRequest request, Principal principal) {
        validateManualPaymentEvidence(request);
        String actor = principal == null ? "" : normalize(principal.getName());
        mergeInvoicePaymentMethod(invoice, PAYMENT_METHOD_MANUAL);
        invoice.setManualPaidBy(actor);
        invoice.setManualPaymentComment(normalize(request.comment()));
        invoice.setManualPaymentReceiptUrl(normalize(request.receiptUrl()));
        invoice.setManualConfirmedAt(LocalDateTime.now());
        applyManualPaymentEvidence(item, request, actor);
        invoiceRepository.save(invoice);
    }

    void applyManualPaymentEvidence(CommonInvoiceOrder item, ManualPaymentConfirmationRequest request, String actor) {
        item.setPaymentMethod(PAYMENT_METHOD_MANUAL);
        item.setManualPaidBy(actor);
        item.setManualPaymentComment(normalize(request.comment()));
        item.setManualPaymentReceiptUrl(normalize(request.receiptUrl()));
    }

    void validateManualPaymentEvidence(ManualPaymentConfirmationRequest request) {
        String comment = normalize(request == null ? null : request.comment());
        String receiptUrl = normalize(request == null ? null : request.receiptUrl());
        if (comment.isBlank() && receiptUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для ручного подтверждения укажите комментарий или ссылку на чек");
        }
    }

    void mergeInvoicePaymentMethod(CommonInvoice invoice, String method) {
        settlementService.mergeInvoicePaymentMethod(invoice, method);
    }

    boolean hasFrozenCommonPaymentRoute(CommonInvoice invoice) {
        return CommonInvoiceRouteState.hasFrozenCommonPaymentRoute(invoice);
    }

    String validateCommonInvoiceManualCardPaymentReason(CommonInvoiceManualCardPaymentRequest request) {
        String reason = normalize(request == null ? null : request.reason());
        if (reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите причину ручной оплаты");
        }
        if (reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Причина не должна превышать 500 символов");
        }
        return reason;
    }

    String validateCommonManualPaymentAttributionRequest(CommonManualPaymentAttributionRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.finalAccountingAcknowledged()) || !Boolean.TRUE.equals(request.paymentReceived())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите фактическое поступление и финальное изменение расчётов");
        }
        if (request.effectiveAt() == null || request.attributions() == null || request.attributions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заполните данные фактического поступления");
        }
        String reason = normalize(request.reason());
        if (reason.isBlank() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Причина ручной оплаты обязательна и не должна превышать 500 символов");
        }
        return reason;
    }

    void ensureNoCurrentCommonTbankPaymentForManualCard(CommonInvoice invoice) {
        boolean hasProviderEvidence = !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank() || !normalize(invoice.getTbankTerminalKey()).isBlank() || invoice.getTbankPaymentAmountKopecks() != null || invoice.getTbankPaymentCreatedAt() != null || !normalize(invoice.getPaymentUrl()).isBlank();
        if (hasProviderEvidence) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У общего счета есть собственный T-Bank платеж. Сначала сверьте или закройте его.");
        }
    }

    void ensureCommonPaymentRefsSafeForManualCard(List<CommonInvoicePaymentRef> refs) {
        List<String> unsafeStatuses = (refs == null ? List.<CommonInvoicePaymentRef>of() : refs).stream().map(this::paymentRefStatus).filter(status -> !MANUAL_COMMON_PAYMENT_SAFE_REF_STATUSES.contains(status)).distinct().toList();
        if (!unsafeStatuses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "В реестре общего счета есть незавершенный T-Bank платеж: " + String.join(", ", unsafeStatuses));
        }
    }

    long remainingKopecks(CommonInvoice invoice) {
        return settlementService.remainingKopecks(invoice);
    }

    Long paymentRefInvoiceId(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefInvoiceId(ref);
    }

    void archiveAndClearCurrentPaymentRef(CommonInvoice invoice, String reason) {
        settlementService.archiveAndClearCurrentPaymentRef(invoice, reason);
    }

    Optional<CommonInvoicePaymentRef> lockedPaymentRefByProviderBinding(String tbankOrderId, String tbankPaymentId) {
        return settlementService.lockedPaymentRefByProviderBinding(tbankOrderId, tbankPaymentId);
    }

    int cancelAttempts(CommonInvoicePaymentRef ref) {
        return invoiceCancellation.cancelAttempts(ref);
    }

    boolean canCancelInitializedPaymentRef(CommonInvoicePaymentRef ref) {
        return settlementService.canCancelInitializedPaymentRef(ref);
    }

    void clearCurrentPaymentRef(CommonInvoice invoice) {
        settlementService.clearCurrentPaymentRef(invoice);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }

    record PreparedManualPaymentBankReconciliation(Long invoiceId, CommonInvoiceStatus originalStatus, Long providerPaymentRefId, String tbankOrderId, String paymentId, String terminalKey, long amountKopecks, String knownProviderStatus) {

        private static PreparedManualPaymentBankReconciliation completed(Long invoiceId) {
            return new PreparedManualPaymentBankReconciliation(invoiceId, CommonInvoiceStatus.READY, null, "", "", "", 0L, "");
        }
    }

    record ManualPaymentBankReconciliationResult(boolean reconciled, HttpStatus failureStatus, String failureMessage) {

        private static ManualPaymentBankReconciliationResult reconciledSuccessfully() {
            return new ManualPaymentBankReconciliationResult(true, null, "");
        }

        private static ManualPaymentBankReconciliationResult failed(HttpStatus status, String message) {
            String cleanMessage = message == null ? "" : message.trim();
            return new ManualPaymentBankReconciliationResult(false, status == null ? HttpStatus.CONFLICT : status, cleanMessage.isBlank() ? "Не удалось сверить T-Bank" : cleanMessage);
        }
    }

    record ManualPaymentBankReconciliationCompletion(ManualPaymentBankReconciliationResult reconciliation, CommonInvoiceDetailsResponse details) {

        private static ManualPaymentBankReconciliationCompletion completed(CommonInvoiceDetailsResponse details) {
            return new ManualPaymentBankReconciliationCompletion(ManualPaymentBankReconciliationResult.reconciledSuccessfully(), details);
        }

        private static ManualPaymentBankReconciliationCompletion failed(ManualPaymentBankReconciliationResult result) {
            return new ManualPaymentBankReconciliationCompletion(result, null);
        }
    }

    record PaperInvoiceBankResolution(boolean switchAllowed, String providerStatus, String durableRefStatus, HttpStatus failureStatus, String failureMessage) {

        static PaperInvoiceBankResolution safe(String providerStatus) {
            String cleanStatus = providerStatus == null || providerStatus.isBlank() ? PAYMENT_REF_CANCELED : providerStatus;
            return new PaperInvoiceBankResolution(true, cleanStatus, cleanStatus, null, "");
        }

        static PaperInvoiceBankResolution blockedByPayment(String providerStatus) {
            String cleanStatus = providerStatus == null || providerStatus.isBlank() ? PAYMENT_REF_CONFIRMED : providerStatus;
            return new PaperInvoiceBankResolution(false, cleanStatus, cleanStatus, HttpStatus.CONFLICT, "T-Bank сообщил статус " + cleanStatus + ": по прежней ссылке есть платёжное движение. Бумажный режим не включён; нужна сверка");
        }

        static PaperInvoiceBankResolution ambiguous(HttpStatus status, String message) {
            return new PaperInvoiceBankResolution(false, "UNKNOWN", PAYMENT_REF_CANCEL_FAILED, status == null ? HttpStatus.CONFLICT : status, message);
        }
    }
}
