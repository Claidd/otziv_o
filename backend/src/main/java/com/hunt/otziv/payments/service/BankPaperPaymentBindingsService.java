package com.hunt.otziv.payments.service;

import com.hunt.otziv.common_billing.api.BankPaperInvoiceBindings;
import com.hunt.otziv.payments.api.BankPaperPaymentBindings;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class BankPaperPaymentBindingsService implements BankPaperPaymentBindings {
    private final PaymentLinkRepository links;
    private final BankPaperInvoiceBindings common;
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public boolean hasDocument(long orderId) {
        return !links.findBankDocumentBindingsForUpdate(orderId).isEmpty() || common.hasDocument(orderId);
    }
}
