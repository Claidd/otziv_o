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
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /** Attention set when the client's cached link outlived the bank session (see the payment init). */
    public static final String EXPIRED_LINK_ATTENTION_PREFIX = "payment_init_conflict: предыдущая T-Bank ссылка истекла";

    static final String EXPIRED_LINK_REF_REASON = "payment_link_expired_before_replacement";

    /** T-Bank states of a session that took no money and cannot take it any more. */
    static final Set<String> NEVER_PAID_BANK_STATUSES = Set.of("REJECTED", "DEADLINE_EXPIRED", "CANCELED", "AUTH_FAIL");

    /** Open or unapplied payments that keep an expired-link invoice for a manual check. */
    static final Set<String> EXPIRED_LINK_RELEASE_BLOCKING_REF_STATUSES = Set.of(
            PAYMENT_REF_CURRENT, PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCELING, PAYMENT_REF_CANCEL_FAILED,
            PAYMENT_REF_CANCEL_FAILED_FINAL, PAYMENT_REF_CONFIRMED, PAYMENT_REF_PREPAID, PAYMENT_REF_APPLYING,
            PAYMENT_REF_INIT_PREPARED, PAYMENT_REF_INIT_CONFLICT);

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
        TbankPaymentProfile runtimeProfile;
        try {
            Optional<PaymentProfile> profile = paymentProfileService.findByTerminalKey(prepared.terminalKey());
            if (profile.isEmpty()) {
                return PAYMENT_REF_CANCEL_FAILED;
            }
            runtimeProfile = paymentProfileService.toRuntimeForTerminal(profile.get(), prepared.terminalKey());
            if (runtimeProfile == null || !runtimeProfile.hasCredentials()) {
                return PAYMENT_REF_CANCEL_FAILED;
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось подготовить отмену архивной T-Bank ссылки общего счета ref={}", prepared.refId(), e);
            return PAYMENT_REF_CANCEL_FAILED;
        }
        try {
            TbankCancelResponse response = tbankClient.cancel(runtimeProfile, new TbankCancelCommand(prepared.paymentId(), prepared.amountKopecks()));
            if (response.success()) {
                // Cancel of a paid session is a refund at the bank: keep the bank's real outcome so a
                // late CONFIRMED webhook can be recognised as a refunded payment, not a new one.
                String bankStatus = response.status() == null ? "" : response.status().trim().toUpperCase(java.util.Locale.ROOT);
                if (Set.of("REFUNDED", "PARTIAL_REFUNDED", "REVERSED", "PARTIAL_REVERSED").contains(bankStatus)) {
                    return bankStatus;
                }
                return PAYMENT_REF_CANCELED;
            }
            log.warn("T-Bank Cancel для архивной ссылки общего счета ref={} вернул отказ: {}", prepared.refId(), response.errorText());
        } catch (RuntimeException e) {
            log.warn("Не удалось отменить архивную T-Bank ссылку общего счета ref={}", prepared.refId(), e);
        }
        // T-Bank refuses to cancel a session it already rejected or let expire ("Невозможно отменить
        // транзакцию в статусе REJECTED"). Such a session took no money and never will, so retrying the
        // cancel for a day only keeps the invoice blocked: ask the bank and close the link instead.
        return neverPaidAtBank(runtimeProfile, prepared) ? PAYMENT_REF_CANCELED : PAYMENT_REF_CANCEL_FAILED;
    }

    private boolean neverPaidAtBank(TbankPaymentProfile runtimeProfile, PreparedArchivedPaymentCancel prepared) {
        try {
            TbankGetStateResponse state = tbankClient.getState(runtimeProfile, prepared.paymentId());
            String bankStatus = state == null ? "" : normalize(state.status()).toUpperCase(Locale.ROOT);
            boolean samePayment = state != null && prepared.paymentId().equals(normalize(state.paymentId()));
            if (samePayment && NEVER_PAID_BANK_STATUSES.contains(bankStatus)) {
                log.info("Архивная T-Bank ссылка общего счета ref={} закрыта банком без оплаты: {}", prepared.refId(), bankStatus);
                return true;
            }
            log.warn("Архивная T-Bank ссылка общего счета ref={} не отменена; состояние в банке: {}",
                    prepared.refId(), bankStatus.isBlank() ? "неизвестно" : bankStatus);
        } catch (RuntimeException e) {
            log.warn("Не удалось проверить состояние архивной T-Bank ссылки общего счета ref={}: {}", prepared.refId(), e.getMessage());
        }
        return false;
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
        } else if (PAYMENT_REF_CANCELED.equals(normalizedStatus)) {
            releaseExpiredLinkAttention(invoice);
        }
    }

    /**
     * Runs the cancel of this invoice's expired archived links now instead of waiting for the
     * scheduler, then releases the invoice if the bank confirmed the old session took no money.
     * Used when the client presses "pay" and by the manager's repair; returns whether the invoice
     * left the expired-link attention.
     */
    public boolean releaseExpiredLinkNow(Long invoiceId) {
        if (invoiceId == null) {
            return false;
        }
        List<Long> refIds = writeTransaction(() -> {
            CommonInvoice invoice = lockedInvoice(invoiceId).orElse(null);
            if (!isExpiredLinkAttention(invoice)) {
                return List.<Long>of();
            }
            List<Long> ids = new java.util.ArrayList<>();
            for (String status : List.of(PAYMENT_REF_CANCEL_PENDING, PAYMENT_REF_CANCEL_FAILED, PAYMENT_REF_CANCELING)) {
                paymentRefRepository.findByInvoiceIdAndStatusForUpdate(invoiceId, status).stream()
                        .filter(ref -> EXPIRED_LINK_REF_REASON.equals(normalize(ref.getReason())))
                        .map(CommonInvoicePaymentRef::getId)
                        .forEach(ids::add);
            }
            return ids;
        });
        for (Long refId : refIds) {
            PreparedArchivedPaymentCancel prepared = writeTransaction(() -> prepareArchivedPaymentCancel(refId, invoiceId));
            if (prepared == null) {
                continue;
            }
            String status = cancelArchivedPayment(prepared);
            writeTransaction(() -> {
                finishArchivedPaymentCancel(prepared, status);
                return null;
            });
        }
        return writeTransaction(() -> {
            CommonInvoice invoice = lockedInvoice(invoiceId).orElse(null);
            if (invoice == null) {
                return false;
            }
            releaseExpiredLinkAttention(invoice);
            return !isExpiredLinkAttention(invoice);
        });
    }

    boolean isExpiredLinkAttention(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && normalize(invoice.getLastError()).startsWith(EXPIRED_LINK_ATTENTION_PREFIX);
    }

    /**
     * An expired link only parks the invoice while its old bank session is being closed. Once no
     * archived session can still take money and no other payment is open, the invoice returns to
     * its sending state: reminders resume, the client gets a new link and the manager can record a
     * payment. Anything else stays for a manual check.
     */
    boolean releaseExpiredLinkAttention(CommonInvoice invoice) {
        if (!isExpiredLinkAttention(invoice) || invoice.getId() == null || invoice.getSentAt() == null
                || !normalize(invoice.getTbankOrderId()).isBlank() || !normalize(invoice.getTbankPaymentId()).isBlank()) {
            return false;
        }
        if (paymentRefRepository.existsByInvoice_IdAndStatusIn(invoice.getId(), EXPIRED_LINK_RELEASE_BLOCKING_REF_STATUSES)) {
            return false;
        }
        invoice.setStatus(invoice.getPaidKopecks() > 0 ? CommonInvoiceStatus.PARTIALLY_PAID
                : invoice.getLastReminderAt() != null ? CommonInvoiceStatus.REMINDER : CommonInvoiceStatus.INVOICED);
        invoice.setLastError(null);
        invoice.setNextReminderAt(settlementService.nextAutomaticPaymentReminderAt(LocalDateTime.now()));
        invoiceRepository.save(invoice);
        log.info("Общий счет {} возвращен в работу: старая T-Bank ссылка закрыта без оплаты", invoice.getId());
        return true;
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
