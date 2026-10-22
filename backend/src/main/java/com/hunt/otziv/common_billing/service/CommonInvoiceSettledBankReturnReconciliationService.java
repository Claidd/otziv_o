package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.payments.api.SettledBankPaymentObservation;
import com.hunt.otziv.payments.api.SettledBankPaymentObservation.Observation;
import com.hunt.otziv.payments.api.SettledBankPaymentObservation.Request;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Checks bank truth after a common invoice payment was already applied. A lost
 * refund webhook must not leave a paid invoice silently trusted forever. The
 * check never reverses per-order accounting automatically: those allocations
 * require a recipient-by-recipient review after an exact provider return.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CommonInvoiceSettledBankReturnReconciliationService {

    private static final String T_BANK = "T_BANK";
    private static final String TOCHKA = "TOCHKA";
    private static final Set<String> SETTLED_REF_STATUSES = Set.of("APPLIED", "PREPAID", "CONFIRMED");
    private static final int BATCH_SIZE = 25;

    private final CommonInvoiceSettlementService settlementService;
    private final CommonInvoicePaymentRefRepository paymentRefRepository;
    private final CommonInvoiceRepository invoiceRepository;
    private final SettledBankPaymentObservation bankObservation;

    @Scheduled(
            fixedDelayString = "${common-billing.settled-bank-reconcile.fixed-delay:PT5M}",
            initialDelayString = "${common-billing.settled-bank-reconcile.initial-delay:PT2M}"
    )
    public void reconcileDueSettledBankRefs() {
        reconcileDueSettledBankRefs(BATCH_SIZE);
    }

    int reconcileDueSettledBankRefs(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<CommonInvoicePaymentRef> candidates = paymentRefRepository.findSettledBankReturnCheckCandidates(
                Set.of(T_BANK, TOCHKA), SETTLED_REF_STATUSES, now,
                PageRequest.of(0, Math.max(1, limit)));
        int checked = 0;
        for (CommonInvoicePaymentRef candidate : candidates) {
            Long refId = candidate.getId();
            Long invoiceId = candidate.getInvoice() == null ? null : candidate.getInvoice().getId();
            try {
                PreparedRef prepared = settlementService.writeTransaction(() -> prepare(refId, invoiceId));
                if (prepared == null) {
                    continue;
                }
                Observation observation = observe(prepared);
                settlementService.writeTransaction(() -> {
                    apply(prepared, observation);
                    return null;
                });
                checked++;
            } catch (RuntimeException failure) {
                log.warn("Settled common bank ref reconciliation failed: refId={}, invoiceId={}, cause={}",
                        refId, invoiceId, failure.getClass().getSimpleName());
            }
        }
        if (checked > 0) {
            log.info("Settled common bank refs checked: {}", checked);
        }
        return checked;
    }

    private PreparedRef prepare(Long refId, Long invoiceId) {
        if (refId == null || invoiceId == null) {
            return null;
        }
        CommonInvoice invoice = settlementService.lockedInvoice(invoiceId).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(refId).orElse(null);
        if (invoice == null || ref == null || ref.getInvoice() == null
                || !invoiceId.equals(ref.getInvoice().getId()) || !isDueSettledRef(ref, LocalDateTime.now())) {
            return null;
        }
        // The lease rotates page zero even if the provider is unavailable or a
        // process exits between the network call and the application transaction.
        LocalDateTime claimUntil = LocalDateTime.now().plusMinutes(10).truncatedTo(ChronoUnit.MICROS);
        ref.setNextProviderCheckAt(claimUntil);
        paymentRefRepository.save(ref);
        return new PreparedRef(refId, invoiceId, upper(ref.getProvider()), upper(ref.getStatus()),
                CommonInvoicePaymentIdentity.providerOrderId(ref),
                settlementService.providerPaymentId(ref),
                CommonInvoicePaymentIdentity.providerMerchantId(ref),
                ref.getPaymentProfileId(), ref.getAmountKopecks(), normalized(ref.getProviderPaymentMode()),
                normalized(ref.getProviderPaymentType()), ref.getProviderTestMode(), claimUntil);
    }

    private Observation observe(PreparedRef ref) {
        try {
            return bankObservation.observe(new Request(ref.provider(), ref.providerOrderId(),
                    ref.providerPaymentId(), ref.merchantId(), ref.profileId(), ref.amountKopecks(),
                    ref.paymentMode(), ref.paymentType(), ref.testMode()));
        } catch (RuntimeException failure) {
            log.warn("Settled common bank provider GET failed: refId={}, provider={}, cause={}",
                    ref.refId(), ref.provider(), failure.getClass().getSimpleName());
            return Observation.temporarilyUnavailable();
        }
    }

    private void apply(PreparedRef prepared, Observation observation) {
        CommonInvoice invoice = settlementService.lockedInvoice(prepared.invoiceId()).orElse(null);
        CommonInvoicePaymentRef ref = paymentRefRepository.findByIdForUpdate(prepared.refId()).orElse(null);
        if (invoice == null || ref == null || !sameClaimedRef(prepared, ref)) {
            return;
        }
        ref.setNextProviderCheckAt(LocalDateTime.now().plusHours(observation.unavailable() ? 1 : 24));
        if (observation.returnStatus() != null) {
            // A common invoice may have multiple positions and another payment.
            // Quarantine the invoice and retain paid amounts until each position
            // is reconciled against actual receipts and any subsequent payment.
            ref.setStatus(observation.returnStatus());
            ref.setProviderStatus(observation.providerStatus());
            ref.setReason("late_bank_return_verified_by_provider_get");
            attention(invoice, "bank_payment_returned: подтверждён возврат "
                    + observation.returnStatus() + " по банковской ссылке #" + ref.getId()
                    + "; требуется сверка позиций и начислений");
            log.warn("Provider return detected for settled common invoice: invoiceId={}, refId={}, provider={}, status={}",
                    invoice.getId(), ref.getId(), prepared.provider(), observation.returnStatus());
        } else if (observation.problemCode() != null) {
            attention(invoice, "bank_payment_reconciliation_required: банковская ссылка #" + ref.getId()
                    + " требует проверки (" + observation.problemCode() + ")");
        }
        paymentRefRepository.save(ref);
    }

    private boolean sameClaimedRef(PreparedRef prepared, CommonInvoicePaymentRef current) {
        return current.getInvoice() != null
                && Objects.equals(prepared.invoiceId(), current.getInvoice().getId())
                && prepared.provider().equals(upper(current.getProvider()))
                && prepared.status().equals(upper(current.getStatus()))
                && prepared.providerOrderId().equals(CommonInvoicePaymentIdentity.providerOrderId(current))
                && prepared.providerPaymentId().equals(settlementService.providerPaymentId(current))
                && prepared.merchantId().equals(CommonInvoicePaymentIdentity.providerMerchantId(current))
                && Objects.equals(prepared.profileId(), current.getPaymentProfileId())
                && Objects.equals(prepared.amountKopecks(), current.getAmountKopecks())
                && prepared.paymentMode().equals(normalized(current.getProviderPaymentMode()))
                && prepared.paymentType().equals(normalized(current.getProviderPaymentType()))
                && Objects.equals(prepared.testMode(), current.getProviderTestMode())
                && Objects.equals(prepared.claimUntil(), current.getNextProviderCheckAt());
    }

    private boolean isDueSettledRef(CommonInvoicePaymentRef ref, LocalDateTime now) {
        return Set.of(T_BANK, TOCHKA).contains(upper(ref.getProvider()))
                && SETTLED_REF_STATUSES.contains(upper(ref.getStatus()))
                && (ref.getNextProviderCheckAt() == null || !ref.getNextProviderCheckAt().isAfter(now));
    }

    private void attention(CommonInvoice invoice, String message) {
        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setNextReminderAt(null);
        invoice.setPaymentUrl(null);
        invoice.setLastError(message.length() > 512 ? message.substring(0, 512) : message);
        invoiceRepository.save(invoice);
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String upper(String value) {
        return normalized(value).toUpperCase(Locale.ROOT);
    }

    private record PreparedRef(Long refId, Long invoiceId, String provider, String status,
                               String providerOrderId, String providerPaymentId, String merchantId,
                               Long profileId, Long amountKopecks, String paymentMode,
                               String paymentType, Boolean testMode, LocalDateTime claimUntil) {
    }

}
