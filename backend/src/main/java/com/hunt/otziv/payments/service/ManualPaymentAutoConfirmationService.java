package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ManualPaymentAutoConfirmationService {

    private static final Set<PaymentMethod> MANUAL_PAYMENT_METHODS = Set.of(
            PaymentMethod.MANUAL_MOBILE_BANK,
            PaymentMethod.MANUAL_EXTERNAL_LINK
    );
    private static final Set<PaymentMethod> BANK_PAYMENT_METHODS = Set.of(
            PaymentMethod.BANK_FORM,
            PaymentMethod.SBP_QR
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
    private static final Set<PaymentLinkStatus> BANK_REVIEW_STATUSES =
            Set.copyOf(EnumSet.allOf(PaymentLinkStatus.class));
    private static final String DEFAULT_CONFIRMED_BY = "order-status:Оплачено";
    private static final String RETIRED_REASON = "Заказ отмечен оплаченным вручную; старая ссылка закрыта";

    private final PaymentLinkRepository paymentLinkRepository;
    private final ManualPaymentTaskService manualPaymentTaskService;
    private final PaymentSuccessNotificationDeliveryService paymentSuccessNotificationDeliveryService;
    private final ContractorPaymentShadowService contractorPaymentShadowService;

    @Transactional(readOnly = true)
    public void ensureCanCloseOrderManually(Order order) {
        if (order == null || order.getId() == null) {
            return;
        }

        List<PaymentLink> links = paymentLinkRepository
                .findByOrder_IdAndStatusIn(order.getId(), BANK_REVIEW_STATUSES);
        boolean hasOrdinaryManualPayment = links.stream().anyMatch(link ->
                link != null
                        && MANUAL_PAYMENT_METHODS.contains(link.getPaymentMethod())
                        && (CONFIRMABLE_STATUSES.contains(link.getStatus())
                            || link.getStatus() == PaymentLinkStatus.CONFIRMED
                            || link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED)
        );
        boolean hasBankPaymentInProgress = links.stream()
                .anyMatch(link -> requiresPrivilegedBankRouteReconciliation(link, hasOrdinaryManualPayment));

        if (hasBankPaymentInProgress) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед ручным закрытием."
            );
        }
    }

    private boolean requiresPrivilegedBankRouteReconciliation(
            PaymentLink link,
            boolean hasOrdinaryManualPayment
    ) {
        if (link == null || !BANK_PAYMENT_METHODS.contains(link.getPaymentMethod())) {
            return false;
        }
        if (!normalize(link.getBankInitNonce()).isBlank()
                || !normalize(link.getBankCancelNonce()).isBlank()
                || link.getBankCancelOriginStatus() != null) {
            return true;
        }
        if (link.getStatus() == PaymentLinkStatus.CANCELED
                || link.getStatus() == PaymentLinkStatus.REJECTED
                || link.getStatus() == PaymentLinkStatus.EXPIRED) {
            if (normalize(link.getTbankPaymentId()).isBlank()) {
                return !hasOrdinaryManualPayment;
            }
            // A provider-created route can be ignored by the generic manual
            // flow only when its matching terminal state was durably observed
            // and an ordinary manual instruction is the current route.
            return !hasOrdinaryManualPayment || !hasAuthoritativeProviderTerminalStatus(link);
        }
        if (link.getStatus() == PaymentLinkStatus.FAILED
                && normalize(link.getTbankPaymentId()).isBlank()) {
            return !hasOrdinaryManualPayment;
        }
        // CREATED is already a public /pay route; INITIATED/AUTHORIZED and all
        // ambiguous, paid, reversed or refunded states are always blocking.
        return true;
    }

    private boolean hasAuthoritativeProviderTerminalStatus(PaymentLink link) {
        if (link == null) {
            return false;
        }
        String providerStatus = normalize(link.getProviderTerminalStatus());
        return switch (link.getStatus()) {
            case CANCELED -> "CANCELED".equalsIgnoreCase(providerStatus);
            case REJECTED -> "REJECTED".equalsIgnoreCase(providerStatus);
            case EXPIRED -> "DEADLINE_EXPIRED".equalsIgnoreCase(providerStatus);
            default -> false;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
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
        List<PaymentLink> retiredLinks = new java.util.ArrayList<>();
        for (PaymentLink link : links) {
            if (link.getStatus() == PaymentLinkStatus.CONFIRMED || hasStartedBankPayment(link)) {
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
            retiredLinks.add(link);
            retired++;
        }
        if (!retiredLinks.isEmpty()) {
            paymentLinkRepository.saveAll(retiredLinks);
        }
        return retired;
    }

    private boolean hasStartedBankPayment(PaymentLink link) {
        if (link == null) {
            return false;
        }
        if (link.getBankInitNonce() != null && !link.getBankInitNonce().isBlank()) {
            return true;
        }
        if ((link.getBankCancelNonce() != null && !link.getBankCancelNonce().isBlank())
                || link.getBankCancelOriginStatus() != null) {
            return true;
        }
        if (link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION) {
            return true;
        }
        if (link.getTbankPaymentId() == null || link.getTbankPaymentId().isBlank()) {
            return false;
        }
        return switch (link.getStatus()) {
            case REJECTED, REVERSED, REFUNDED -> false;
            case CANCELED, EXPIRED -> link.getLastError() != null && !link.getLastError().isBlank();
            default -> true;
        };
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
        paymentLinkRepository.save(link);
        manualPaymentTaskService.completeIfConfirmedTargetReached(link.getManualPaymentTask());
        paymentSuccessNotificationDeliveryService.deliverAfterCommit(link.getId());
        reconcileContractorRouteAfterCommit(link.getId());
    }

    private void reconcileContractorRouteAfterCommit(Long paymentLinkId) {
        if (paymentLinkId == null) {
            return;
        }
        Runnable reconcile = () -> {
            try {
                contractorPaymentShadowService.reconcilePaymentLinkId(paymentLinkId);
            } catch (RuntimeException exception) {
                // The confirmed PaymentLink remains a durable retry source for
                // ContractorShadowRouteBackfillService.
                log.error(
                        "Не удалось сразу сверить назначение оплаченной ссылки linkId={}, code={}",
                        paymentLinkId,
                        exception.getClass().getSimpleName()
                );
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reconcile.run();
                }
            });
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn(
                    "Пропущена немедленная сверка оплаченной ссылки без transaction synchronization linkId={}",
                    paymentLinkId
            );
            return;
        }
        reconcile.run();
    }
}
