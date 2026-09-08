package com.hunt.otziv.common_billing.api;

import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.tochka.dto.TochkaAcquiringInternetPaymentWebhook;
import java.time.LocalDateTime;
import java.util.Map;

/** Synchronous settlement boundary; implementations own invoice locks and atomic ledger changes. */
public interface CommonInvoicePaymentOperations {
    boolean isOrderInActiveCommonInvoice(Long orderId);
    boolean applyConfirmedOrderPayment(Long orderId, LocalDateTime paidAt, String reason);
    boolean applyStandalonePaymentReversal(Long orderId, Long paymentLinkId, PaymentLinkStatus terminalStatus);
    boolean handleTbankWebhook(Map<String, String> payload);
    boolean handleTochkaWebhook(TochkaAcquiringInternetPaymentWebhook claims);
}
