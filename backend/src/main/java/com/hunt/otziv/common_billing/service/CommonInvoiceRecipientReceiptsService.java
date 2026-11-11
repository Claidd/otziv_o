package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.api.CommonInvoiceRecipientReceipts;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentNotificationOutboxRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads and marks the recipient notices kept in the common-invoice notification outbox. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceRecipientReceiptsService implements CommonInvoiceRecipientReceipts {

    private final CommonInvoicePaymentNotificationOutboxRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Receipt> find(long deliveryId) {
        return repository.findRecipientReceipt(deliveryId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markDisputed(long deliveryId, long telegramUserId) {
        return repository.markRecipientReceiptDisputed(deliveryId, telegramUserId);
    }
}
