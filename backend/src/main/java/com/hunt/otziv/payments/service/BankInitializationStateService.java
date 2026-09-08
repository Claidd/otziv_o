package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.CONFIRMED_LIKE_BANK_STATUSES;
import static com.hunt.otziv.payments.service.PaymentLinkCancellationWorkflow.REFUND_OR_REVERSAL_BANK_STATUSES;

/**
 * Shared reservation and quarantine transitions used by creation, public init,
 * and recovery. Mutations require the caller's locked transaction; this owner
 * never calls a payment provider or creates a replacement payment.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BankInitializationStateService {

    private final PaymentLinkLifecycleService lifecycleService;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    static final String BANK_INIT_AMBIGUOUS_PREFIX = "bank_init_ambiguous:";

    private final PaymentLinkRepository paymentLinkRepository;

    private boolean hasBankInitReservation(PaymentLink link) {
        return lifecycleService.hasBankInitReservation(link);
    }

    private boolean isTochkaPaymentLink(PaymentLink link) {
        return paymentPresenter.isTochkaPaymentLink(link);
    }

    private boolean hasBankCancelReservation(PaymentLink link) {
        return paymentPresenter.hasBankCancelReservation(link);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void handleExistingBankInitReservationBeforePayableValidation(PaymentLink link, LocalDateTime now) {
        BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(link, now, "public_retry");
        if (recovery == BankInitReservationRecovery.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Инициализация платежа уже выполняется. Повторите запрос через несколько секунд.");
        }
        if (recovery == BankInitReservationRecovery.QUARANTINED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Результат предыдущей инициализации неизвестен. Платеж требует сверки администратором.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void recoverOrderBankInitReservationsBeforeCreation(List<PaymentLink> orderLinks, LocalDateTime now) {
        boolean quarantined = false;
        for (PaymentLink link : orderLinks == null ? List.<PaymentLink>of() : orderLinks) {
            BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(link, now, "manager_create");
            if (recovery == BankInitReservationRecovery.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Инициализация предыдущего платежа еще выполняется. Новый счет заблокирован.");
            }
            quarantined |= recovery == BankInitReservationRecovery.QUARANTINED;
        }
        if (quarantined) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущая инициализация платежа требует сверки. Новый счет заблокирован.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void ensureNoCompetingBankPaymentForInit(PaymentLink current, List<PaymentLink> orderLinks, LocalDateTime now) {
        boolean quarantined = false;
        List<PaymentLink> safeLinks = orderLinks == null ? List.of() : orderLinks;
        for (PaymentLink link : safeLinks) {
            BankInitReservationRecovery recovery = recoverExpiredBankInitReservationLocked(link, now, "public_order_guard");
            if (recovery == BankInitReservationRecovery.ACTIVE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Инициализация платежа по заказу уже выполняется");
            }
            quarantined |= recovery == BankInitReservationRecovery.QUARANTINED;
        }
        if (quarantined) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Предыдущий платеж по заказу требует сверки администратором");
        }
        boolean competingPayment = safeLinks.stream().filter(link -> !sameLinkId(current, link)).anyMatch(this::blocksCreationOfAnotherBankPayment);
        if (competingPayment) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "По заказу уже существует другой банковский платеж");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    BankInitReservationRecovery recoverExpiredBankInitReservationLocked(PaymentLink link, LocalDateTime now, String source) {
        if (!hasBankInitReservation(link)) {
            return BankInitReservationRecovery.NONE;
        }
        if (link.getBankInitLeaseUntil() != null && link.getBankInitLeaseUntil().isAfter(now)) {
            return BankInitReservationRecovery.ACTIVE;
        }
        boolean missingPaymentId = normalize(link.getTbankPaymentId()).isBlank();
        boolean expired = link.getExpiresAt() != null && link.getExpiresAt().isBefore(now);
        boolean amountChanged = isAmountChanged(link);
        if (missingPaymentId && isTochkaPaymentLink(link)) {
            if (isBankInitBusinessStateAuthoritative(link.getStatus())) {
                clearBankInitReservation(link);
                paymentLinkRepository.save(link);
                return BankInitReservationRecovery.RETRYABLE_RECOVERED;
            }
            // Do not clear the reservation: a successful POST with a lost response must be
            // recovered by the scheduler using the stable paymentLinkId. Public/manager
            // transactions intentionally do not perform provider I/O.
            link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
            link.setPaymentUrl(null);
            link.setLastError(limit(BANK_INIT_AMBIGUOUS_PREFIX + " tochka_create_requires_get_recovery; source=" + normalize(source), 512));
            paymentLinkRepository.save(link);
            return BankInitReservationRecovery.QUARANTINED;
        }
        if (missingPaymentId || expired || amountChanged) {
            String reason = missingPaymentId ? "reservation_expired_without_provider_result" : expired ? "reservation_expired_after_link_deadline" : "reservation_expired_after_amount_change";
            quarantineAmbiguousBankInit(link, link.getTbankPaymentId(), reason + "; source=" + normalize(source));
            return BankInitReservationRecovery.QUARANTINED;
        }
        // Init already supplied a PaymentId and only a follow-up operation
        // (for example GetQr) was interrupted. Retrying that follow-up on the
        // same immutable payment binding is safe.
        clearBankInitReservation(link);
        paymentLinkRepository.save(link);
        return BankInitReservationRecovery.RETRYABLE_RECOVERED;
    }

    boolean blocksCreationOfAnotherBankPayment(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (hasBankInitReservation(link) || hasBankCancelReservation(link) || link.getBankCancelOriginStatus() != null) {
            return true;
        }
        if (link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
            return true;
        }
        if (normalize(link.getTbankPaymentId()).isBlank()) {
            return false;
        }
        return switch(link.getStatus()) {
            case REJECTED, REVERSED, REFUNDED ->
                false;
            case CANCELED, EXPIRED ->
                !normalize(link.getLastError()).isBlank();
            default ->
                true;
        };
    }

    boolean hasCompetingBlockingPayment(PaymentLink current, List<PaymentLink> orderLinks) {
        return (orderLinks == null ? List.<PaymentLink>of() : orderLinks).stream().filter(link -> !sameLinkId(current, link)).anyMatch(this::blocksCreationOfAnotherBankPayment);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    boolean quarantineAmbiguousBankInit(PaymentLink link, String paymentId, String reason) {
        String observedPaymentId = normalize(paymentId);
        if (normalize(link.getTbankPaymentId()).isBlank() && !observedPaymentId.isBlank()) {
            link.setTbankPaymentId(observedPaymentId);
        }
        boolean quarantined = !isBankInitBusinessStateAuthoritative(link.getStatus());
        if (quarantined) {
            link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        }
        if (link.getInitiatedAt() == null && !observedPaymentId.isBlank()) {
            link.setInitiatedAt(LocalDateTime.now());
        }
        if (quarantined) {
            link.setPaymentUrl(null);
        }
        clearBankInitReservation(link);
        if (quarantined) {
            link.setLastError(limit(BANK_INIT_AMBIGUOUS_PREFIX + " " + normalize(reason), 512));
        }
        paymentLinkRepository.save(link);
        return quarantined;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void clearBankInitReservation(PaymentLink link) {
        link.setBankInitNonce(null);
        link.setBankInitLeaseUntil(null);
    }

    boolean canApplyBankInitResponseTo(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.CREATED || status == PaymentLinkStatus.INITIATED || status == PaymentLinkStatus.AUTHORIZED || CONFIRMED_LIKE_BANK_STATUSES.contains(status) || REFUND_OR_REVERSAL_BANK_STATUSES.contains(status);
    }

    boolean isBankInitBusinessStateAuthoritative(PaymentLinkStatus status) {
        return status == PaymentLinkStatus.AUTHORIZED || status == PaymentLinkStatus.CANCELED || status == PaymentLinkStatus.EXPIRED || status == PaymentLinkStatus.REJECTED || CONFIRMED_LIKE_BANK_STATUSES.contains(status) || REFUND_OR_REVERSAL_BANK_STATUSES.contains(status);
    }

    private boolean sameLinkId(PaymentLink left, PaymentLink right) {
        Long leftId = left == null ? null : left.getId();
        Long rightId = right == null ? null : right.getId();
        return leftId != null && leftId.equals(rightId);
    }

    private boolean isAmountChanged(PaymentLink link) {
        return lifecycleService.isAmountChanged(link);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }

    enum BankInitReservationRecovery {

        NONE, ACTIVE, RETRYABLE_RECOVERED, QUARANTINED
    }
}
