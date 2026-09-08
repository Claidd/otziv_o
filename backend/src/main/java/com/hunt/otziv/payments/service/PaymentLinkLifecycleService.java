package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared payment availability and retirement rules. Mutations join the caller's
 * locked transaction and preserve its rollback policy, including an expiry
 * persisted before a public no-rollback validation error.
 */
@Service
@RequiredArgsConstructor
public class PaymentLinkLifecycleService {

    private final PaymentLinkAmountPolicy amountPolicy;

    private final PaymentLinkPresenter paymentPresenter;

    static final Set<PaymentLinkStatus> RECREATABLE_STALE_STATUSES = Set.of(PaymentLinkStatus.CREATED, PaymentLinkStatus.INITIATED, PaymentLinkStatus.WAITING_MANUAL_PAYMENT);

    private final PaymentLinkRepository paymentLinkRepository;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final OrderPaymentIntegrityService orderPaymentIntegrityService;

    boolean canRetireStaleLink(PaymentLink link) {
        return link != null && RECREATABLE_STALE_STATUSES.contains(link.getStatus()) && !hasStartedBankPayment(link);
    }

    boolean hasStartedBankPayment(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (hasBankInitReservation(link)) {
            return true;
        }
        return (link.getPaymentMethod() == PaymentMethod.BANK_FORM || link.getPaymentMethod() == PaymentMethod.SBP_QR) && !normalize(link.getTbankPaymentId()).isBlank();
    }

    boolean hasBankInitReservation(PaymentLink link) {
        return link != null && !normalize(link.getBankInitNonce()).isBlank();
    }

    boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return link != null && orderId != null && link.getOrder() != null && orderId.equals(link.getOrder().getId());
    }

    void validateTbankPayment(PaymentLink link) {
        if (paymentPresenter.isManualPayment(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Эта ссылка создана для ручной оплаты");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void validatePayable(PaymentLink link) {
        validatePayable(link, false);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void validatePayable(PaymentLink link, boolean releaseTaskReservationOnExpiry) {
        orderPaymentIntegrityService.assertPaymentCycleAllowed(link == null ? null : link.getOrder());
        if (link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж уже создан в банке и требует сверки. Повторная оплата заблокирована.");
        }
        if (link.getStatus() == PaymentLinkStatus.AUTHORIZED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж уже авторизован банком. Повторная инициализация заблокирована.");
        }
        if (link.getExpiresAt().isBefore(LocalDateTime.now())) {
            if (releaseTaskReservationOnExpiry) {
                taskReceiptIntegrationService.release(link, "Срок действия платёжной ссылки истек");
            }
            link.setStatus(PaymentLinkStatus.EXPIRED);
            throw new ResponseStatusException(HttpStatus.GONE, "Срок действия платежной ссылки истек");
        }
        if (expireIfAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.GONE, "Сумма заказа изменилась. Создайте новую ссылку на оплату.");
        }
        if (isAmountChanged(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма заказа изменилась, а платеж уже в процессе. Проверьте платеж вручную.");
        }
        if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ уже оплачен");
        }
        if (link.getStatus() == PaymentLinkStatus.AMOUNT_MISMATCH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платеж пришел по устаревшей сумме и требует ручной сверки");
        }
        if (link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Тестовый платеж по ссылке уже подтвержден");
        }
        if (link.getStatus() == PaymentLinkStatus.CANCELED || link.getStatus() == PaymentLinkStatus.REVERSED || link.getStatus() == PaymentLinkStatus.PARTIAL_REVERSED || link.getStatus() == PaymentLinkStatus.REFUNDED || link.getStatus() == PaymentLinkStatus.PARTIAL_REFUNDED || link.getStatus() == PaymentLinkStatus.REJECTED || link.getStatus() == PaymentLinkStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платежная ссылка недоступна");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    void expireIfPastDue(PaymentLink link) {
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now()) && !hasBankInitReservation(link) && (link.getStatus() == PaymentLinkStatus.WAITING_MANUAL_PAYMENT || link.getStatus() == PaymentLinkStatus.MANUAL_REPORTED || link.getStatus() == PaymentLinkStatus.CREATED)) {
            taskReceiptIntegrationService.release(link, "Срок действия платежной ссылки истек");
            link.setStatus(PaymentLinkStatus.EXPIRED);
            link.setLastError("Срок действия платежной ссылки истек");
            paymentLinkRepository.save(link);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Exception.class)
    boolean expireIfAmountChanged(PaymentLink link) {
        if (!canRetireStaleLink(link) || !isAmountChanged(link)) {
            return false;
        }
        taskReceiptIntegrationService.release(link, "Сумма заказа изменилась; старый резерв освобожден");
        long currentAmount = currentAmountKopecks(link);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setLastError("Сумма заказа изменилась: было " + amountRubles(link.getAmountKopecks()).stripTrailingZeros().toPlainString() + " руб., стало " + amountRubles(currentAmount).stripTrailingZeros().toPlainString() + " руб.");
        paymentLinkRepository.save(link);
        return true;
    }

    boolean isAmountChanged(PaymentLink link) {
        return link != null && currentAmountKopecks(link) != link.getAmountKopecks();
    }

    private long currentAmountKopecks(PaymentLink link) {
        return amountPolicy.currentAmountKopecks(link);
    }

    private BigDecimal amountRubles(long amountKopecks) {
        return paymentPresenter.amountRubles(amountKopecks);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }
}
