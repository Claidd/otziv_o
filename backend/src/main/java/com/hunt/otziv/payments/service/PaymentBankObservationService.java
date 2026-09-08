package com.hunt.otziv.payments.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.dto.TbankGetStateResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfile;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentInfoResponse;
import com.hunt.otziv.payments.tochka.dto.TochkaApiModels.PaymentOperation;
import com.hunt.otziv.payments.tochka.dto.TochkaPaymentProfile;
import com.hunt.otziv.payments.tochka.model.TochkaPaymentMode;
import com.hunt.otziv.payments.tochka.service.TochkaClient;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.ExpectedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentProfileResolver;
import com.hunt.otziv.payments.tochka.service.TochkaProviderException;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.u_users.model.Manager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.hunt.otziv.logs.util.LogMasking.maskPaymentId;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentBankObservationService {

    boolean isTochkaRefundStatus(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.REFUNDED || status == PaymentLinkStatus.PARTIAL_REFUNDED;
    }

    static final Set<PaymentLinkStatus> SYNCABLE_BANK_STATUSES = Set.of(PaymentLinkStatus.INITIATED, PaymentLinkStatus.AUTHORIZED, PaymentLinkStatus.NEEDS_RECONCILIATION, PaymentLinkStatus.PARTIAL_REVERSED, PaymentLinkStatus.PARTIAL_REFUNDED);

    static final Duration PUBLIC_BANK_STATE_MIN_INTERVAL = Duration.ofSeconds(10);

    private final TbankRuntimeSettingsService runtimeSettingsService;

    private final PaymentProfileService paymentProfileService;

    private final TbankClient tbankClient;

    private final TochkaPaymentProfileResolver tochkaPaymentProfileResolver;

    private final TochkaClient tochkaClient;

    private final TochkaPaymentOperationMapper tochkaPaymentOperationMapper;

    private final ConcurrentMap<String, LocalDateTime> publicBankStateClaims = new ConcurrentHashMap<>();

    /**
     * Performs the provider request without a surrounding database transaction.
     * Its result is only an observation; all changes are applied after a fresh
     * pessimistic read of the payment-link row.
     */
    BankStateObservation observeTbankState(PaymentLink link) {
        if (!shouldObserveTbankState(link)) {
            return null;
        }
        return requestTbankState(link);
    }

    BankStateObservation observeTbankStateForManualCardPayment(PaymentLink link) {
        if (link == null || !runtimeSettingsService.isTbankEnabled() || isTochkaPaymentLink(link) || (link.getPaymentMethod() != PaymentMethod.BANK_FORM && link.getPaymentMethod() != PaymentMethod.SBP_QR) || normalize(link.getTbankPaymentId()).isBlank()) {
            return null;
        }
        return requestTbankState(link);
    }

    BankStateObservation requestTbankState(PaymentLink link) {
        String paymentId = normalize(link.getTbankPaymentId());
        try {
            PaymentProfile profile = resolvePaymentProfile(link);
            TbankPaymentProfile runtimeProfile = runtimeProfileForLink(profile, link);
            TbankGetStateResponse state = tbankClient.getState(runtimeProfile, paymentId);
            return new BankStateObservation(link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), normalize(link.getToken()), paymentId, normalize(link.getTbankOrderId()), normalize(runtimeProfile.terminalKey()), link.getAmountKopecks(), link.getStatus(), state);
        } catch (ResponseStatusException e) {
            log.warn("T-Bank GetState sync skipped: linkId={}, paymentId={}, status={}, reason={}", link.getId(), maskPaymentId(paymentId), e.getStatusCode(), normalize(e.getReason()));
        } catch (RuntimeException e) {
            log.warn("T-Bank GetState sync failed: linkId={}, paymentId={}", link.getId(), maskPaymentId(paymentId), e);
        }
        return null;
    }

    /**
     * A public payment page may be reloaded several times at once (focus,
     * pageshow and a manual refresh). Keep those reads public, but do not turn
     * every reload into another provider request. The durable timestamp also
     * coordinates the public path with scheduled reconciliation; the local
     * atomic claim closes the gap before that timestamp is committed.
     */
    PublicBankStateProbe observePublicBankState(PaymentLink link) {
        if (!shouldObserveBankState(link)) {
            return PublicBankStateProbe.skipped();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime eligibleBefore = now.minus(PUBLIC_BANK_STATE_MIN_INTERVAL);
        if (link.getBankReconciliationAttemptedAt() != null && link.getBankReconciliationAttemptedAt().isAfter(eligibleBefore)) {
            return PublicBankStateProbe.skipped();
        }
        String claimKey = link.getId() == null ? "token:" + normalize(link.getToken()) : "id:" + link.getId();
        AtomicBoolean claimed = new AtomicBoolean(false);
        publicBankStateClaims.compute(claimKey, (ignored, previous) -> {
            if (previous != null && previous.isAfter(eligibleBefore)) {
                return previous;
            }
            claimed.set(true);
            return now;
        });
        if (!claimed.get()) {
            return PublicBankStateProbe.skipped();
        }
        if (publicBankStateClaims.size() > 10_000) {
            publicBankStateClaims.entrySet().removeIf(entry -> !entry.getValue().isAfter(eligibleBefore));
        }
        return new PublicBankStateProbe(true, now, observeBankState(link));
    }

    boolean shouldObserveTbankState(PaymentLink link) {
        return link != null && runtimeSettingsService.isTbankEnabled() && !isTochkaPaymentLink(link) && (SYNCABLE_BANK_STATUSES.contains(link.getStatus()) || link.getBankCancelOriginStatus() != null) && !normalize(link.getTbankPaymentId()).isBlank();
    }

    boolean shouldObserveBankState(PaymentLink link) {
        if (isTochkaPaymentLink(link)) {
            return link != null && (SYNCABLE_BANK_STATUSES.contains(link.getStatus()) || link.getBankCancelOriginStatus() != null) && !normalize(link.getTbankPaymentId()).isBlank();
        }
        return shouldObserveTbankState(link);
    }

    ProviderStateObservation observeBankState(PaymentLink link) {
        return isTochkaPaymentLink(link) ? observeTochkaState(link) : observeTbankState(link);
    }

    boolean isTochkaPaymentLink(PaymentLink link) {
        return link != null && link.getPaymentProfile() != null && paymentProfileService.isTochkaProvider(link.getPaymentProfile());
    }

    TochkaStateObservation observeTochkaState(PaymentLink link) {
        if (!shouldObserveBankState(link) || !isTochkaPaymentLink(link)) {
            return null;
        }
        String operationId = normalize(link.getTbankPaymentId());
        PaymentProfile entityProfile = link.getPaymentProfile();
        Long profileId = entityProfile == null ? null : entityProfile.getId();
        TochkaPaymentMode expectedMode;
        TochkaPaymentProfile runtimeProfile;
        try {
            expectedMode = expectedTochkaMode(link.getPaymentMethod());
            runtimeProfile = tochkaPaymentProfileResolver.resolveForExistingPayment(entityProfile);
            if (!normalize(link.getTbankTerminalKey()).equals(runtimeProfile.merchantId())) {
                return failedTochkaObservation(link, profileId, expectedMode, "tochka_profile_merchant_binding_mismatch");
            }
        } catch (RuntimeException failure) {
            return failedTochkaObservation(link, profileId, null, "tochka_profile_or_mode_validation_failed: " + tochkaProviderFailureReason(failure));
        }
        PaymentInfoResponse response;
        try {
            response = tochkaClient.getPaymentInfo(runtimeProfile, operationId);
        } catch (ResponseStatusException failure) {
            log.warn("Tochka payment status sync skipped: linkId={}, operationId={}, status={}, reason={}", link.getId(), maskPaymentId(operationId), failure.getStatusCode(), normalize(failure.getReason()));
            return null;
        } catch (RuntimeException failure) {
            log.warn("Tochka payment status sync failed: linkId={}, operationId={}", link.getId(), maskPaymentId(operationId), failure);
            return null;
        }
        try {
            PaymentOperation operation = requireSingleTochkaOperation(response);
            MappedPayment mapped = tochkaPaymentOperationMapper.map(operation, new ExpectedPayment(operationId, normalize(link.getTbankOrderId()), runtimeProfile.customerCode(), runtimeProfile.merchantId(), link.getAmountKopecks(), expectedMode), false);
            return new TochkaStateObservation(link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), normalize(link.getToken()), operationId, normalize(link.getTbankOrderId()), runtimeProfile.merchantId(), runtimeProfile.customerCode(), runtimeProfile.testMode(), link.getAmountKopecks(), link.getStatus(), link.getPaymentMethod(), profileId, expectedMode, mapped, null);
        } catch (RuntimeException failure) {
            return failedTochkaObservation(link, profileId, expectedMode, "tochka_state_identity_or_status_validation_failed: " + tochkaProviderFailureReason(failure));
        }
    }

    PaymentOperation requireSingleTochkaOperation(PaymentInfoResponse response) {
        List<PaymentOperation> operations = response == null || response.data() == null || response.data().operations() == null ? List.of() : response.data().operations().stream().filter(Objects::nonNull).toList();
        if (operations.size() != 1) {
            throw new TochkaProviderException("Точка API не вернула единственную ожидаемую платежную операцию", false, null);
        }
        return operations.getFirst();
    }

    TochkaStateObservation failedTochkaObservation(PaymentLink link, Long profileId, TochkaPaymentMode expectedMode, String failureReason) {
        return new TochkaStateObservation(link.getId(), link.getOrder() == null ? null : link.getOrder().getId(), normalize(link.getToken()), normalize(link.getTbankPaymentId()), normalize(link.getTbankOrderId()), normalize(link.getTbankTerminalKey()), "", false, link.getAmountKopecks(), link.getStatus(), link.getPaymentMethod(), profileId, expectedMode, null, limit(failureReason, 512));
    }

    TochkaPaymentMode expectedTochkaMode(PaymentMethod paymentMethod) {
        return switch(paymentMethod) {
            case BANK_FORM ->
                TochkaPaymentMode.CARD;
            case SBP_QR ->
                TochkaPaymentMode.SBP;
            default ->
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Способ оплаты платежной ссылки не соответствует Точке");
        };
    }

    String tochkaProviderFailureReason(RuntimeException failure) {
        if (failure instanceof ResponseStatusException statusException) {
            String reason = normalize(statusException.getReason());
            return reason.isBlank() ? "Tochka provider call failed" : reason;
        }
        return failure == null || normalize(failure.getMessage()).isBlank() ? "Tochka provider call failed" : normalize(failure.getMessage());
    }

    PaymentProfile resolvePaymentProfile(PaymentLink link) {
        if (link.getPaymentProfile() != null) {
            return link.getPaymentProfile();
        }
        String terminalKey = normalize(link.getTbankTerminalKey());
        if (!terminalKey.isBlank()) {
            return paymentProfileService.findByTerminalKey(terminalKey).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "TerminalKey платежной ссылки не найден в настройках T-Bank"));
        }
        String profileCode = normalize(link.getPaymentProfileCode());
        if (!profileCode.isBlank()) {
            return paymentProfileService.findByCode(profileCode).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Платежный профиль T-Bank не найден в настройках"));
        }
        return selectProfile(link.getOrder());
    }

    TbankPaymentProfile runtimeProfileForLink(PaymentProfile profile, PaymentLink link) {
        String terminalKey = normalize(link.getTbankTerminalKey());
        if (terminalKey.isBlank()) {
            return paymentProfileService.toRuntime(profile);
        }
        return paymentProfileService.toRuntimeForTerminal(profile, terminalKey);
    }

    PaymentProfile selectProfile(Order order) {
        return paymentProfileService.selectForManager(orderManager(order));
    }

    Manager orderManager(Order order) {
        Manager manager = order == null ? null : order.getManager();
        if (manager == null && order != null) {
            Company company = order.getCompany();
            manager = company == null ? null : company.getManager();
        }
        return manager;
    }

    String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    String limit(String value, int maxLength) {
        String clean = normalize(value);
        if (clean.length() <= maxLength) {
            return clean;
        }
        return clean.substring(0, maxLength);
    }

    interface ProviderStateObservation {

        Long linkId();

        Long orderId();

        String token();

        long amountKopecks();

        PaymentLinkStatus status();
    }

    record BankStateObservation(Long linkId, Long orderId, String token, String paymentId, String tbankOrderId, String terminalKey, long amountKopecks, PaymentLinkStatus status, TbankGetStateResponse state) implements ProviderStateObservation {
    }

    record TochkaStateObservation(Long linkId, Long orderId, String token, String operationId, String paymentLinkId, String merchantId, String customerCode, boolean testMode, long amountKopecks, PaymentLinkStatus status, PaymentMethod paymentMethod, Long profileId, TochkaPaymentMode expectedMode, MappedPayment mapped, String failureReason) implements ProviderStateObservation {
    }

    record PublicBankStateProbe(boolean attempted, LocalDateTime attemptedAt, ProviderStateObservation observation) {

        private static PublicBankStateProbe skipped() {
            return new PublicBankStateProbe(false, null, null);
        }
    }
}
