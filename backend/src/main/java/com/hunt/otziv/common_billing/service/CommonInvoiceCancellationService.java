package com.hunt.otziv.common_billing.service;

import static com.hunt.otziv.common_billing.service.CommonInvoicePresenter.*;
import static com.hunt.otziv.common_billing.service.CommonInvoiceSettlementService.*;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.payments.dto.TbankCancelCommand;
import com.hunt.otziv.payments.dto.TbankCancelResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.CLOSE_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceCancellationService {

    private final CommonInvoiceSettlementService settlementService;

    static final int PAYMENT_REF_CANCEL_MAX_ATTEMPTS = 144;

    static final java.time.Duration PAYMENT_REF_CANCEL_RETRY_DELAY = java.time.Duration.ofMinutes(10);

    static final java.time.Duration PAYMENT_REF_CANCELING_TIMEOUT = java.time.Duration.ofMinutes(30);

    private final CommonInvoiceRepository invoiceRepository;

    private final CommonInvoicePaymentRefRepository paymentRefRepository;

    private final PaymentProfileService paymentProfileService;

    private final TbankClient tbankClient;

    public int cancelPendingArchivedPayments(int limit) {
        List<CommonInvoicePaymentRef> refs = paymentRefRepository.findCancelableRefs(PROVIDER_TBANK, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_INIT_CONFLICT, PAYMENT_REF_CANCELING, LocalDateTime.now().minus(PAYMENT_REF_CANCEL_RETRY_DELAY), LocalDateTime.now().minus(PAYMENT_REF_CANCELING_TIMEOUT), PAYMENT_REF_CANCEL_MAX_ATTEMPTS, PageRequest.of(0, Math.max(1, limit)));
        int processed = 0;
        for (CommonInvoicePaymentRef candidate : refs) {
            Long candidateInvoiceId = paymentRefInvoiceId(candidate);
            PreparedArchivedPaymentCancel prepared = writeTransaction(() -> prepareArchivedPaymentCancel(candidate.getId(), candidateInvoiceId));
            if (prepared == null) {
                continue;
            }
            String status = cancelArchivedPayment(prepared);
            writeTransaction(() -> {
                finishArchivedPaymentCancel(prepared, status);
                return null;
            });
            processed++;
        }
        return processed;
    }

    Optional<CommonInvoice> lockedInvoice(Long invoiceId) {
        return settlementService.lockedInvoice(invoiceId);
    }

    <T> T writeTransaction(Supplier<T> action) {
        return settlementService.writeTransaction(action);
    }

    ResponseStatusException invoiceMembershipChanged(String detail) {
        return settlementService.invoiceMembershipChanged(detail);
    }

    PreparedArchivedPaymentCancel prepareArchivedPaymentCancel(Long refId, Long candidateInvoiceId) {
        Long expectedInvoiceId = candidateInvoiceId == null ? paymentRefRepository.findInvoiceIdById(refId).orElse(null) : candidateInvoiceId;
        CommonInvoice invoice = expectedInvoiceId == null ? null : lockedInvoice(expectedInvoiceId).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(refId).orElse(null);
        if (ref == null) {
            return null;
        }
        if (!Objects.equals(expectedInvoiceId, paymentRefInvoiceId(ref))) {
            throw invoiceMembershipChanged("архивная платежная ссылка сменила общий счет");
        }
        String status = normalize(ref == null ? null : ref.getStatus());
        if ((!PAYMENT_REF_CANCEL_PENDING.equals(status) && !PAYMENT_REF_CANCEL_FAILED.equals(status) && !PAYMENT_REF_INIT_CONFLICT.equals(status) && !PAYMENT_REF_CANCELING.equals(status))) {
            return null;
        }
        if (PAYMENT_REF_CANCELING.equals(status) && !isStaleArchivedPaymentCancel(ref)) {
            return null;
        }
        if ((PAYMENT_REF_CANCEL_FAILED.equals(status) || PAYMENT_REF_CANCELING.equals(status)) && cancelAttempts(ref) >= PAYMENT_REF_CANCEL_MAX_ATTEMPTS) {
            markArchivedPaymentCancelFailedFinal(ref, invoice);
            return null;
        }
        if (invoice != null && invoice.getStatus() == CommonInvoiceStatus.PAID) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            ref.setReason(limit("paid_invoice_cancel_skipped", 160));
            paymentRefRepository.save(ref);
            log.warn("Автоотмена архивной T-Bank ссылки общего счета пропущена: ref={}, invoice={} уже PAID", ref.getId(), invoice.getId());
            return null;
        }
        String paymentId = normalize(ref.getTbankPaymentId());
        String terminalKey = normalize(ref.getTbankTerminalKey());
        Long amount = ref.getAmountKopecks();
        if (paymentId.isBlank() || terminalKey.isBlank() || amount == null || amount <= 0) {
            ref.setStatus(PAYMENT_REF_ARCHIVED);
            paymentRefRepository.save(ref);
            return null;
        }
        ref.setStatus(PAYMENT_REF_CANCELING);
        ref.setCancelAttempts(cancelAttempts(ref) + 1);
        paymentRefRepository.save(ref);
        return new PreparedArchivedPaymentCancel(ref.getId(), expectedInvoiceId, paymentId, terminalKey, amount);
    }

    String cancelArchivedPayment(PreparedArchivedPaymentCancel prepared) {
        try {
            Optional<PaymentProfile> profile = paymentProfileService.findByTerminalKey(prepared.terminalKey());
            if (profile.isEmpty()) {
                return PAYMENT_REF_CANCEL_FAILED;
            }
            TbankPaymentProfile runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile.get(), prepared.terminalKey());
            if (!runtimeProfile.hasCredentials()) {
                return PAYMENT_REF_CANCEL_FAILED;
            }
            TbankCancelResponse response = tbankClient.cancel(runtimeProfile, new TbankCancelCommand(prepared.paymentId(), prepared.amountKopecks()));
            if (response.success()) {
                return PAYMENT_REF_CANCELED;
            }
            log.warn("T-Bank Cancel для архивной ссылки общего счета ref={} вернул отказ: {}", prepared.refId(), response.errorText());
            return PAYMENT_REF_CANCEL_FAILED;
        } catch (RuntimeException e) {
            log.warn("Не удалось отменить архивную T-Bank ссылку общего счета ref={}", prepared.refId(), e);
            return PAYMENT_REF_CANCEL_FAILED;
        }
    }

    void finishArchivedPaymentCancel(PreparedArchivedPaymentCancel prepared, String status) {
        if (prepared == null) {
            return;
        }
        CommonInvoice invoice = prepared.invoiceId() == null ? null : lockedInvoice(prepared.invoiceId()).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(prepared.refId()).orElse(null);
        if (ref == null || !PAYMENT_REF_CANCELING.equals(normalize(ref.getStatus()))) {
            return;
        }
        if (!Objects.equals(prepared.invoiceId(), paymentRefInvoiceId(ref))) {
            throw invoiceMembershipChanged("архивная платежная ссылка сменила общий счет");
        }
        String normalizedStatus = normalize(status);
        if (PAYMENT_REF_CANCEL_FAILED.equals(normalizedStatus) && cancelAttempts(ref) >= PAYMENT_REF_CANCEL_MAX_ATTEMPTS) {
            normalizedStatus = PAYMENT_REF_CANCEL_FAILED_FINAL;
        }
        ref.setStatus(limit(normalizedStatus, 32));
        paymentRefRepository.save(ref);
        if (PAYMENT_REF_CANCEL_FAILED_FINAL.equals(normalizedStatus)) {
            markInvoiceNeedsAttentionForFinalCancelFailure(ref, invoice);
        }
    }

    void markArchivedPaymentCancelFailedFinal(CommonInvoicePaymentRef ref, CommonInvoice invoice) {
        if (ref == null) {
            return;
        }
        ref.setStatus(PAYMENT_REF_CANCEL_FAILED_FINAL);
        paymentRefRepository.save(ref);
        markInvoiceNeedsAttentionForFinalCancelFailure(ref, invoice);
    }

    void markInvoiceNeedsAttentionForFinalCancelFailure(CommonInvoicePaymentRef ref, CommonInvoice invoice) {
        if (invoice == null) {
            return;
        }
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setLastError(limit(PAYMENT_CANCEL_FAILED_FINAL + ": старая T-Bank ссылка " + paymentRefLabel(ref) + " не отменена после " + cancelAttempts(ref) + " попыток; проверьте банк вручную", 512));
        invoiceRepository.save(invoice);
    }

    Long paymentRefInvoiceId(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefInvoiceId(ref);
    }

    int cancelAttempts(CommonInvoicePaymentRef ref) {
        Integer attempts = ref == null ? null : ref.getCancelAttempts();
        return attempts == null ? 0 : Math.max(0, attempts);
    }

    boolean isStaleArchivedPaymentCancel(CommonInvoicePaymentRef ref) {
        LocalDateTime updatedAt = ref == null ? null : ref.getUpdatedAt();
        return updatedAt != null && !updatedAt.plus(PAYMENT_REF_CANCELING_TIMEOUT).isAfter(LocalDateTime.now());
    }

    String paymentRefLabel(CommonInvoicePaymentRef ref) {
        return settlementService.paymentRefLabel(ref);
    }

    String paymentRefLabel(String tbankOrderId, String tbankPaymentId) {
        return settlementService.paymentRefLabel(tbankOrderId, tbankPaymentId);
    }

    String normalize(String value) {
        return settlementService.normalize(value);
    }

    String limit(String value, int max) {
        return settlementService.limit(value, max);
    }

    record PreparedArchivedPaymentCancel(Long refId, Long invoiceId, String paymentId, String terminalKey, long amountKopecks) {
    }
}
