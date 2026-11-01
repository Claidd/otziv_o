package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.api.BankPaperInvoiceBindings;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class BankPaperInvoiceBindingsService implements BankPaperInvoiceBindings {
    private final JdbcTemplate jdbc;
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public boolean hasDocument(long orderId) {
        return jdbc.queryForList("""
            SELECT i.paper_bank_document_id FROM common_invoice_orders o
            JOIN common_invoices i ON i.invoice_id=o.invoice_id
            WHERE o.order_id=? AND o.active_membership=1 FOR UPDATE
            """,String.class,orderId).stream().anyMatch(java.util.Objects::nonNull);
    }
}
