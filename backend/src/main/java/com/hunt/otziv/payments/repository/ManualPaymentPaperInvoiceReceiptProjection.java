package com.hunt.otziv.payments.repository;

import java.time.LocalDateTime;

/**
 * An order paid by «Счёт и акт» (bank documents of the owner): the payment and the owner who
 * received it, the one linked to the manager of the order. The owner is {@code null} when the
 * manager has none.
 */
public interface ManualPaymentPaperInvoiceReceiptProjection {

    Long getSourceId();

    Long getAmountKopecks();

    LocalDateTime getEffectiveAt();

    Long getOwnerUserId();

    String getOwnerName();
}
