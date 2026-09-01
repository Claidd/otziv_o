package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.PaymentRouteChangeContextResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class PaymentRouteChangeService {

    private final PaymentLinkService paymentLinkService;
    private final PaymentRouteChangeNotificationWorker notificationWorker;

    @Transactional(readOnly = true)
    public PaymentRouteChangeContextResponse context(Long orderId, Authentication authentication) {
        return paymentLinkService.paymentRouteChangeContextAuthorized(orderId, authentication);
    }

    @Transactional
    public PaymentRouteChangeResponse change(
            Long orderId,
            PaymentRouteChangeRequest request,
            Authentication authentication
    ) {
        PaymentLinkService.PaymentRouteReplacement replacement =
                paymentLinkService.replacePaymentRouteAuthorized(
                        orderId,
                        request.expectedPaymentLinkId(),
                        request.target(),
                        request.confirmedUnpaid(),
                        request.expectedTargetPaymentProfileId(),
                        authentication
                );
        Runnable notification = replacement.response() == null
                ? null
                : () -> notificationWorker.send(replacement.paymentLinkId());
        if (notification != null) {
            // This insert participates in the route-change transaction.  The
            // scheduler can therefore recover a committed replacement even if
            // the process dies before afterCommit executes.
            notificationWorker.enqueue(
                    orderId,
                    replacement.paymentLinkId(),
                    replacement.response()
            );
        }
        if (notification != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notification.run();
                }
            });
        } else if (notification != null) {
            notification.run();
        }
        return new PaymentRouteChangeResponse(
                replacement.previousPaymentLinkId(),
                replacement.paymentLinkId(),
                replacement.target(),
                notification != null,
                replacement.response()
        );
    }
}
