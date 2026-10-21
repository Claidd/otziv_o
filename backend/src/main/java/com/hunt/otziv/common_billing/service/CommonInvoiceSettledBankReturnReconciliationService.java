package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankClient;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
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
    private static final Set<String> TBANK_RETURN_STATUSES = Set.of(
            "REVERSED", "PARTIAL_REVERSED", "REFUNDED", "PARTIAL_REFUNDED");
    private static final int BATCH_SIZE = 25;

    private final CommonInvoiceSettlementService settlementService;
    private final CommonInvoicePaymentRefRepository paymentRefRepository;
    private final CommonInvoiceRepository invoiceRepository;
    private final PaymentProfileService paymentProfileService;
    private final TbankClient tbankClient;
    private final TochkaClient tochkaClient;
    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;
    private final TochkaPaymentOperationMapper tochkaPaymentOperationMapper;

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
                ProviderObservation observation = observe(prepared);
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

    private ProviderObservation observe(PreparedRef ref) {
        if (ref.amountKopecks() == null || ref.amountKopecks() <= 0
                || ref.providerOrderId().isBlank() || ref.providerPaymentId().isBlank()
                || ref.merchantId().isBlank()) {
            return ProviderObservation.ambiguous("bank_ref_identity_incomplete");
        }
        try {
            return switch (ref.provider()) {
                case T_BANK -> observeTbank(ref);
                case TOCHKA -> observeTochka(ref);
                default -> ProviderObservation.ambiguous("bank_ref_provider_unknown");
            };
        } catch (RuntimeException failure) {
            log.warn("Settled common bank provider GET failed: refId={}, provider={}, cause={}",
                    ref.refId(), ref.provider(), failure.getClass().getSimpleName());
            return ProviderObservation.temporarilyUnavailable();
        }
    }

    private ProviderObservation observeTbank(PreparedRef ref) {
        PaymentProfile profile = ref.profileId() == null
                ? paymentProfileService.findByTerminalKey(ref.merchantId()).orElse(null)
                : paymentProfileService.findById(ref.profileId()).orElse(null);
        if (profile == null || !T_BANK.equals(upper(profile.normalizedProvider()))) {
            return ProviderObservation.ambiguous("tbank_pinned_profile_unavailable");
        }
        TbankPaymentProfile runtime;
        try {
            runtime = paymentProfileService.toRuntimeForTerminal(profile, ref.merchantId());
        } catch (RuntimeException invalidProfile) {
            return ProviderObservation.ambiguous("tbank_pinned_profile_invalid");
        }
        if (!runtime.hasCredentials() || !ref.merchantId().equals(normalized(runtime.terminalKey()))) {
            return ProviderObservation.ambiguous("tbank_pinned_terminal_changed");
        }
        TbankGetStateResponse state = tbankClient.getState(runtime, ref.providerPaymentId());
        if (state == null || !state.success() || !ref.providerPaymentId().equals(normalized(state.paymentId()))
                || !ref.providerOrderId().equals(normalized(state.orderId()))
                || !Objects.equals(state.amount(), ref.amountKopecks())
                || (!normalized(state.terminalKey()).isBlank()
                    && !ref.merchantId().equals(normalized(state.terminalKey())))) {
            return ProviderObservation.ambiguous("tbank_settled_identity_mismatch");
        }
        String status = upper(state.status());
        if (TBANK_RETURN_STATUSES.contains(status)) {
            return ProviderObservation.returned(status, status);
        }
        return "CONFIRMED".equals(status)
                ? ProviderObservation.confirmed(status)
                : ProviderObservation.ambiguous("tbank_settled_status_changed");
    }

    private ProviderObservation observeTochka(PreparedRef ref) {
        if (ref.profileId() == null || ref.testMode() == null || ref.paymentMode().isBlank()) {
            return ProviderObservation.ambiguous("tochka_pinned_profile_incomplete");
        }
        PaymentProfile profile = paymentProfileService.findById(ref.profileId()).orElse(null);
        if (profile == null || !TOCHKA.equals(upper(profile.normalizedProvider()))) {
            return ProviderObservation.ambiguous("tochka_pinned_profile_unavailable");
        }
        TochkaPaymentProfile runtime;
        try {
            runtime = tochkaPaymentProfileResolver.resolveForExistingPayment(profile);
        } catch (RuntimeException invalidProfile) {
            return ProviderObservation.ambiguous("tochka_pinned_profile_invalid");
        }
        if (!ref.merchantId().equals(normalized(runtime.merchantId()))
                || !Objects.equals(ref.testMode(), runtime.testMode())) {
            return ProviderObservation.ambiguous("tochka_pinned_merchant_changed");
        }
        PaymentInfoResponse response = tochkaClient.getPaymentInfo(runtime, ref.providerPaymentId());
        List<PaymentOperation> operations = response == null || response.data() == null
                ? List.of() : response.data().operations();
        if (operations == null || operations.size() != 1 || operations.getFirst() == null) {
            return ProviderObservation.ambiguous("tochka_settled_operation_ambiguous");
        }
        PaymentOperation operation = operations.getFirst();
        MappedPayment mapped;
        try {
            List<TochkaPaymentMode> modes = settlementService.parseTochkaPaymentModes(ref.paymentMode());
            TochkaPaymentMode observedMode = settlementService.observedTochkaPaymentMode(
                    operation.paymentType(), operation.status(), modes);
            mapped = tochkaPaymentOperationMapper.map(operation,
                    new ExpectedPayment(ref.providerPaymentId(), ref.providerOrderId(),
                            runtime.customerCode(), runtime.merchantId(), ref.amountKopecks(), observedMode), false);
        } catch (RuntimeException invalidObservation) {
            return ProviderObservation.ambiguous("tochka_settled_identity_or_status_mismatch");
        }
        if (!ref.paymentType().isBlank()
                && !ref.paymentType().equals(normalized(mapped.providerPaymentType()))) {
            return ProviderObservation.ambiguous("tochka_settled_payment_type_changed");
        }
        if (mapped.status() == PaymentLinkStatus.REFUNDED) {
            return ProviderObservation.returned("REFUNDED", mapped.providerStatus());
        }
        if (mapped.status() == PaymentLinkStatus.PARTIAL_REFUNDED) {
            return ProviderObservation.returned("PARTIAL_REFUNDED", mapped.providerStatus());
        }
        return mapped.status() == PaymentLinkStatus.CONFIRMED
                ? ProviderObservation.confirmed(mapped.providerStatus())
                : ProviderObservation.ambiguous("tochka_settled_status_changed");
    }

    private void apply(PreparedRef prepared, ProviderObservation observation) {
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

    private record ProviderObservation(String returnStatus, String providerStatus,
                                       String problemCode, boolean unavailable) {
        private static ProviderObservation returned(String status, String providerStatus) {
            return new ProviderObservation(status, providerStatus, null, false);
        }

        private static ProviderObservation confirmed(String providerStatus) {
            return new ProviderObservation(null, providerStatus, null, false);
        }

        private static ProviderObservation ambiguous(String code) {
            return new ProviderObservation(null, null, code, false);
        }

        private static ProviderObservation temporarilyUnavailable() {
            return new ProviderObservation(null, null, null, true);
        }
    }
}
