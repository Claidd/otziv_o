package com.hunt.otziv.payments.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Wakeup only: the payment retry flag and fenced claim are the durable queue. */
@Service
@RequiredArgsConstructor
public class PaymentSuccessNotificationAsyncWakeup {
    private final ObjectProvider<PaymentSuccessNotificationDeliveryService> delivery;

    @Async("clientNotificationExecutor")
    public void dispatch(long paymentLinkId) {
        delivery.getObject().tryDeliver(paymentLinkId);
    }
}
