package com.hunt.otziv.payments.api;

import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import org.springframework.security.core.Authentication;

/** Request a different actual recipient for a single order, waiting for an open bank session. */
public interface OrderBankRecipientChange {
    PaymentRouteChangeResponse order(long orderId, PaymentRouteChangeRequest request, Authentication actor);
}
