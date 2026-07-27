package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManualPaymentAutoConfirmationService {

    private static final Set<PaymentMethod> MANUAL_PAYMENT_METHODS = Set.of(
            PaymentMethod.MANUAL_MOBILE_BANK,
            PaymentMethod.MANUAL_EXTERNAL_LINK
    );
    private static final Set<PaymentLinkStatus> CONFIRMABLE_STATUSES = Set.of(
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentLinkStatus> RETIRABLE_STATUSES = Set.of(
            PaymentLinkStatus.CREATED,
            PaymentLinkStatus.INITIATED,
            PaymentLinkStatus.WAITING_MANUAL_PAYMENT,
            PaymentLinkStatus.MANUAL_REPORTED
    );
    private static final Set<PaymentLinkStatus> BLOCKING_BANK_STATUSES = Set.of(
            PaymentLinkStatus.AUTHORIZED
    );
    private static final Set<PaymentMethod> BANK_PAYMENT_METHODS = Set.of(
            PaymentMethod.BANK_FORM,
            PaymentMethod.SBP_QR
    );
    private static final String DEFAULT_CONFIRMED_BY = "order-status:Оплачено";
    private static final String RETIRED_REASON = "Заказ отмечен оплаченным вручную; старая ссылка закрыта";

    private final PaymentLinkRepository paymentLinkRepository;
    private final ManualPaymentTaskService manualPaymentTaskService;
    private final PaymentSuccessClientNotifier paymentSuccessClientNotifier;

    @Transactional(readOnly = true)
    public void ensureCanCloseOrderManually(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }

        boolean hasBankPaymentInProgress = paymentLinkRepository
                .findByOrder_IdAndStatusIn(order.getId(), BLOCKING_BANK_STATUSES)
                .stream()
                .anyMatch(this::hasStartedBankPayment);

        if (hasBankPaymentInProgress) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа есть авторизованный T-Bank/СБП платеж. Проверьте его в журнале перед ручным закрытием."
            );
        }
    }

    @Transactional
    public void confirmForPaidOrder(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }

        paymentLinkRepository
                .findFirstByOrder_IdAndPaymentMethodInAndStatusInOrderByCreatedAtDesc(
                        order.getId(),
                        MANUAL_PAYMENT_METHODS,
                        CONFIRMABLE_STATUSES
                )
                .ifPresent(this::confirm);
    }

    @Transactional
    public int retireOpenLinksForPaidOrder(Order order) {
        if (order == null || order.getId() == null) {
            return 0;
        }

        List<PaymentLink> links = paymentLinkRepository.findByOrder_IdAndStatusIn(order.getId(), RETIRABLE_STATUSES);
        LocalDateTime now = LocalDateTime.now();
        int retired = 0;
        for (PaymentLink link : links) {
            if (link.getStatus() == PaymentLinkStatus.CONFIRMED) {
                continue;
            }
            link.setStatus(PaymentLinkStatus.CANCELED);
            link.setLastError(RETIRED_REASON);
            if (link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK
                    || link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK) {
                link.setManualConfirmedAt(null);
                link.setManualConfirmedBy(null);
            }
            link.setUpdatedAt(now);
            retired++;
        }
        if (!links.isEmpty()) {
            paymentLinkRepository.saveAll(links);
        }
        return retired;
    }

    private boolean hasStartedBankPayment(PaymentLink link) {
        return link != null
                && BANK_PAYMENT_METHODS.contains(link.getPaymentMethod())
                && link.getTbankPaymentId() != null
                && !link.getTbankPaymentId().isBlank();
    }

    private void confirm(PaymentLink link) {
        LocalDateTime now = LocalDateTime.now();
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(now);
        link.setManualConfirmedAt(now);
        link.setManualConfirmedBy(DEFAULT_CONFIRMED_BY);
        link.setConfirmedAmountKopecks(link.getAmountKopecks());
        link.setReceiptStatus(PaymentReceiptStatus.PENDING);
        link.setLastError(null);
        link.setPaymentSuccessNotificationRetryEligible(true);
        notifyPaymentSuccess(link);
        paymentLinkRepository.save(link);
        manualPaymentTaskService.completeIfConfirmedTargetReached(link.getManualPaymentTask());
    }

    private void notifyPaymentSuccess(PaymentLink link) {
        try {
            ClientMessageSendResult result = paymentSuccessClientNotifier.notifySuccess(link);
            if (result != null && result.sent()) {
                link.setPaymentSuccessNotifiedAt(LocalDateTime.now());
                link.setPaymentSuccessNotificationError(null);
                link.setPaymentSuccessNotificationRetryEligible(false);
                return;
            }
            link.setPaymentSuccessNotificationError(limit(notificationError(result), 512));
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            link.setPaymentSuccessNotificationError(limit(
                    message == null || message.isBlank() ? exception.getClass().getSimpleName() : message,
                    512
            ));
            log.warn("Manual payment success notification failed: linkId={}", link.getId(), exception);
        }
    }

    private String notificationError(ClientMessageSendResult result) {
        if (result == null) {
            return "notification_result_empty";
        }
        String code = normalize(result.errorCode());
        String message = normalize(result.errorMessage());
        if (code.isBlank()) {
            return message.isBlank() ? "notification_not_sent" : message;
        }
        return message.isBlank() ? code : code + ": " + message;
    }

    private String limit(String value, int maxLength) {
        String clean = normalize(value);
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
