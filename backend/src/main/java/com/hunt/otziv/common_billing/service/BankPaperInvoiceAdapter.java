package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.api.BankPaperInvoices;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class BankPaperInvoiceAdapter implements BankPaperInvoices {
    private final CommonInvoiceRepository invoices;
    private final CommonInvoiceDeliveryService delivery;
    private final CommonInvoiceManualPaymentWorkflow settlement;
    private final CommonInvoicePaymentRouteWorkflow routes;
    private final JdbcTemplate jdbc;
    private final com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold recovery;
    @Transactional(readOnly=true)
    public Snapshot read(long id) {
        var invoice=invoices.findById(id).orElseThrow(() -> problem("Общий счёт не найден"));
        delivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
        Long company=jdbc.queryForObject("SELECT a.invoice_company_id FROM common_invoices i JOIN common_billing_accounts a ON a.account_id=i.account_id WHERE i.invoice_id=?",Long.class,id);
        if(company==null) throw problem("Для общего счёта сначала выберите компанию-плательщика");
        return new Snapshot(company,invoice.getAmountKopecks());
    }
    @Transactional
    public Snapshot prepare(long id) {
        var invoice=delivery.lockedInvoiceAfterStandalonePaymentPrelude(id).invoice();
        delivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if(!CommonInvoiceRouteState.isOwnerPaperInvoice(invoice) || invoice.getPaidKopecks()!=0
                || !Set.of("READY","INVOICED","REMINDER").contains(invoice.getStatus().name()))
            throw problem("Общий счёт должен быть полностью готов и не оплачен");
        for(Long order:jdbc.queryForList("SELECT order_id FROM common_invoice_orders WHERE invoice_id=? AND active_membership=1",Long.class,id))
            if(recovery.shouldPauseActiveOrder(order)) throw problem("Есть незавершённые задачи восстановления. Счёт и акт можно выпустить после их завершения");
        return read(id);
    }
    public void configure(long id,Principal actor) { routes.configureBankPaperMode(id,actor); }
    public String deliveryClient(long id,String fallback) {
        var r=jdbc.queryForMap("SELECT a.manager_id,m.client_id FROM common_invoices i JOIN common_billing_accounts a ON a.account_id=i.account_id LEFT JOIN managers m ON m.manager_id=a.manager_id WHERE i.invoice_id=?",id);
        return r.get("manager_id")==null?fallback:r.get("client_id")==null?"":r.get("client_id").toString().trim();
    }
    public String composition(long id) {
        var rows=jdbc.queryForList("SELECT order_id,amount_kopecks FROM common_invoice_orders WHERE invoice_id=? AND active_membership=1 ORDER BY order_id",id);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Transactional
    public void bindDocument(long id,String document) {
        var invoice=invoices.findByIdForUpdate(id).orElseThrow(() -> problem("Общий счёт не найден"));
        invoice.setPaperBankDocumentId(document); invoices.save(invoice);
    }
    public void settle(long id,long amount,String payment) { settlement.applyVerifiedPaperBankPayment(id,amount,payment); }
    public void delivered(long id,String document) { delivery.markBankPaperDocumentsDelivered(id,document); }
    private static ResponseStatusException problem(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
}
