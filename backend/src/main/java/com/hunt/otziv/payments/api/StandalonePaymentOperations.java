package com.hunt.otziv.payments.api;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import java.time.LocalDateTime;

/** Owner API for reconciling or canceling a standalone attempt before common payment. */
public interface StandalonePaymentOperations {
    boolean reconcileBankLink(Long linkId, LocalDateTime attemptBefore);
    AdminPaymentLinkResponse cancel(Long linkId);
    void reconcileActiveOrder(Long orderId);
}
