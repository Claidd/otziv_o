package com.hunt.otziv.common_billing.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CommonInvoiceSendRequestListener {
    private final CommonBillingService billing;
    @EventListener
    public void send(CommonInvoiceAfterCommitSender.Request request) {
        billing.sendInvoiceAutomatically(request.invoiceId(), request.manual());
    }
}
