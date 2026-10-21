package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.api.SettledBankPaymentObservation;
import com.hunt.otziv.payments.api.SettledBankPaymentObservation.Request;
import com.hunt.otziv.payments.api.SettledBankPaymentObservation.Observation;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Payments-owned, read-only provider check for an already settled external
 * invoice. The caller supplies the durable identity; this API never writes a
 * payment, invoice, or order and rejects a provider result with a changed
 * identity or payment mode.
 */
@Service
@RequiredArgsConstructor
public class SettledBankPaymentObservationService implements SettledBankPaymentObservation {

    private static final Set<String> TBANK_RETURN_STATUSES = Set.of(
            "REVERSED", "PARTIAL_REVERSED", "REFUNDED", "PARTIAL_REFUNDED");

    private final PaymentProfileService paymentProfileService;
    private final TbankClient tbankClient;
    private final TochkaClient tochkaClient;
    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;
    private final TochkaPaymentOperationMapper tochkaPaymentOperationMapper;

    @Override
    public Observation observe(Request request) {
        if (request == null || request.amountKopecks() == null || request.amountKopecks() <= 0
                || normalized(request.providerOrderId()).isBlank()
                || normalized(request.providerPaymentId()).isBlank()
                || normalized(request.merchantId()).isBlank()) {
            return Observation.ambiguous("bank_ref_identity_incomplete");
        }
        return switch (upper(request.provider())) {
            case "T_BANK" -> observeTbank(request);
            case "TOCHKA" -> observeTochka(request);
            default -> Observation.ambiguous("bank_ref_provider_unknown");
        };
    }

    private Observation observeTbank(Request request) {
        PaymentProfile profile = request.profileId() == null
                ? paymentProfileService.findByTerminalKey(request.merchantId()).orElse(null)
                : paymentProfileService.findById(request.profileId()).orElse(null);
        if (profile == null || !"T_BANK".equals(upper(profile.normalizedProvider()))) {
            return Observation.ambiguous("tbank_pinned_profile_unavailable");
        }
        TbankPaymentProfile runtime;
        try {
            runtime = paymentProfileService.toRuntimeForTerminal(profile, request.merchantId());
        } catch (RuntimeException invalidProfile) {
            return Observation.ambiguous("tbank_pinned_profile_invalid");
        }
        if (!runtime.hasCredentials() || !request.merchantId().equals(normalized(runtime.terminalKey()))) {
            return Observation.ambiguous("tbank_pinned_terminal_changed");
        }
        TbankGetStateResponse state = tbankClient.getState(runtime, request.providerPaymentId());
        if (state == null || !state.success()
                || !request.providerPaymentId().equals(normalized(state.paymentId()))
                || !request.providerOrderId().equals(normalized(state.orderId()))
                || !Objects.equals(state.amount(), request.amountKopecks())
                || (!normalized(state.terminalKey()).isBlank()
                    && !request.merchantId().equals(normalized(state.terminalKey())))) {
            return Observation.ambiguous("tbank_settled_identity_mismatch");
        }
        String status = upper(state.status());
        if (TBANK_RETURN_STATUSES.contains(status)) {
            return Observation.returned(status, status);
        }
        return "CONFIRMED".equals(status)
                ? Observation.confirmed(status)
                : Observation.ambiguous("tbank_settled_status_changed");
    }

    private Observation observeTochka(Request request) {
        if (request.profileId() == null || request.testMode() == null
                || normalized(request.paymentMode()).isBlank()) {
            return Observation.ambiguous("tochka_pinned_profile_incomplete");
        }
        PaymentProfile profile = paymentProfileService.findById(request.profileId()).orElse(null);
        if (profile == null || !"TOCHKA".equals(upper(profile.normalizedProvider()))) {
            return Observation.ambiguous("tochka_pinned_profile_unavailable");
        }
        TochkaPaymentProfile runtime;
        try {
            runtime = tochkaPaymentProfileResolver.resolveForExistingPayment(profile);
        } catch (RuntimeException invalidProfile) {
            return Observation.ambiguous("tochka_pinned_profile_invalid");
        }
        if (!request.merchantId().equals(normalized(runtime.merchantId()))
                || !Objects.equals(request.testMode(), runtime.testMode())) {
            return Observation.ambiguous("tochka_pinned_merchant_changed");
        }
        PaymentInfoResponse response = tochkaClient.getPaymentInfo(runtime, request.providerPaymentId());
        List<PaymentOperation> operations = response == null || response.data() == null
                ? List.of() : response.data().operations();
        if (operations == null || operations.size() != 1 || operations.getFirst() == null) {
            return Observation.ambiguous("tochka_settled_operation_ambiguous");
        }
        PaymentOperation operation = operations.getFirst();
        MappedPayment mapped;
        try {
            List<TochkaPaymentMode> modes = parseTochkaPaymentModes(request.paymentMode());
            TochkaPaymentMode observedMode = observedTochkaPaymentMode(
                    operation.paymentType(), operation.status(), modes);
            mapped = tochkaPaymentOperationMapper.map(operation,
                    new ExpectedPayment(request.providerPaymentId(), request.providerOrderId(),
                            runtime.customerCode(), runtime.merchantId(), request.amountKopecks(), observedMode), false);
        } catch (RuntimeException invalidObservation) {
            return Observation.ambiguous("tochka_settled_identity_or_status_mismatch");
        }
        if (!normalized(request.paymentType()).isBlank()
                && !normalized(request.paymentType()).equals(normalized(mapped.providerPaymentType()))) {
            return Observation.ambiguous("tochka_settled_payment_type_changed");
        }
        if (mapped.status() == PaymentLinkStatus.REFUNDED) {
            return Observation.returned("REFUNDED", mapped.providerStatus());
        }
        if (mapped.status() == PaymentLinkStatus.PARTIAL_REFUNDED) {
            return Observation.returned("PARTIAL_REFUNDED", mapped.providerStatus());
        }
        return mapped.status() == PaymentLinkStatus.CONFIRMED
                ? Observation.confirmed(mapped.providerStatus())
                : Observation.ambiguous("tochka_settled_status_changed");
    }

    private List<TochkaPaymentMode> parseTochkaPaymentModes(String value) {
        List<TochkaPaymentMode> modes = Arrays.stream(normalized(value).split(","))
                .map(String::trim).filter(mode -> !mode.isBlank())
                .map(TochkaPaymentMode::fromCode).distinct().toList();
        if (modes.isEmpty() || modes.stream().anyMatch(mode -> mode != TochkaPaymentMode.SBP
                && mode != TochkaPaymentMode.CARD)) {
            throw new IllegalArgumentException("Pinned Tochka payment modes are invalid");
        }
        return modes;
    }

    private TochkaPaymentMode observedTochkaPaymentMode(String paymentType, String providerStatus,
                                                        List<TochkaPaymentMode> allowedModes) {
        String cleanType = normalized(paymentType);
        if (cleanType.isBlank() && Set.of("CREATED", "EXPIRED").contains(upper(providerStatus))) {
            return allowedModes.getFirst();
        }
        TochkaPaymentMode observed = TochkaPaymentMode.matchesOperationType(
                cleanType, TochkaPaymentMode.SBP.code(), providerStatus)
                ? TochkaPaymentMode.SBP
                : TochkaPaymentMode.fromCode(cleanType.toLowerCase(Locale.ROOT));
        if (!allowedModes.contains(observed)) {
            throw new IllegalArgumentException("Observed Tochka payment mode differs from durable ref");
        }
        return observed;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String upper(String value) {
        return normalized(value).toUpperCase(Locale.ROOT);
    }

}
