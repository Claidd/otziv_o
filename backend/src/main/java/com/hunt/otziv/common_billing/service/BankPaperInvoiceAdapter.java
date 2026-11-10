package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.payments.model.InvoicePaymentMode;

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
    private final com.hunt.otziv.p_products.api.PaperInvoiceOrderData orders;
    private final com.hunt.otziv.client_messages.api.PaperInvoiceAutomation automation;
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
    @org.springframework.beans.factory.annotation.Autowired private BankRecipientChanges bankChanges;

    public void waitForBankBeforeIssue(long id, org.springframework.security.core.Authentication actor, String documentRequest) {
        bankChanges.paper(id, actor, documentRequest);
    }

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
        invoice.setPaperBankDocumentId(document); invoice.setPaperInvoiceAutomationHold(false); invoices.save(invoice);
        for(long order:memberIds(id)) orders.holdAutomation(order,false);
    }
    private java.util.List<Long> memberIds(long id) {
        return jdbc.queryForList("SELECT order_id FROM common_invoice_orders WHERE invoice_id=? AND active_membership=1 ORDER BY order_id",Long.class,id);
    }
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public void requireUnpaidForCancellation(long id) {
        var invoice=delivery.lockedInvoiceAfterStandalonePaymentPrelude(id).invoice();
        if(invoice.getPaidKopecks()!=0 || invoice.getStatus()==com.hunt.otziv.common_billing.model.CommonInvoiceStatus.PAID)
            throw problem("Общий счёт уже оплачен; отмена документов запрещена");
        for(long order:memberIds(id)) orders.requireUnpaidForCancellation(order);
    }
    @Transactional
    public void holdForPaymentReview(long id) {
        var invoice=invoices.findByIdForUpdate(id).orElse(null);
        if(invoice==null) return; // Late bank evidence and frozen staff recipients still remain valid.
        invoice.setPaperInvoiceAutomationHold(true);invoice.setPaperInvoiceReviewHold(true);invoice.setNextReminderAt(null);invoices.save(invoice);
        for(long order:memberIds(id)) orders.holdForPaymentReview(order);
    }
    @Transactional
    public void holdAutomation(long id,boolean hold) {
        var invoice=delivery.lockedInvoiceAfterStandalonePaymentPrelude(id).invoice();
        if(!hold && invoice.isPaperInvoiceReviewHold()) throw problem("Есть несверенное поступление по отменённому счёту");
        invoice.setPaperInvoiceAutomationHold(hold);if(hold) invoice.setNextReminderAt(null);
        invoices.save(invoice);
        for(long order:memberIds(id)) orders.holdAutomation(order,hold);
    }
    @Transactional
    public void cancelled(long id,String document) {
        var invoice=delivery.lockedInvoiceAfterStandalonePaymentPrelude(id).invoice();
        if(invoice.getPaidKopecks()!=0 || !java.util.Objects.equals(document,invoice.getPaperBankDocumentId())) throw problem("Общий счёт изменился или уже оплачен");
        invoice.setPaperBankDocumentId(null);invoice.setPaperInvoiceIssuedAt(null);invoice.setSentAt(null);
        invoice.setNextReminderAt(null);invoice.setPaperInvoiceAutomationHold(true);
        invoice.setStatus(com.hunt.otziv.common_billing.model.CommonInvoiceStatus.READY);
        invoices.save(invoice);
        for(long order:memberIds(id)) orders.cancelled(order,true);
    }
    @Transactional
    public void resumeOrdinaryInvoice(long id) {
        resumeOrdinaryInvoice(id, InvoicePaymentMode.AUTO_ROUTING);
    }

    @Transactional
    public void resumeOrdinaryInvoice(long id, InvoicePaymentMode mode) {
        var invoice=delivery.lockedInvoiceAfterStandalonePaymentPrelude(id).invoice();
        if(invoice.isPaperInvoiceReviewHold() || !invoice.isPaperInvoiceAutomationHold()
                || !CommonInvoiceRouteState.isOwnerPaperInvoice(invoice) || invoice.getPaidKopecks()!=0
                || invoice.getPaperBankDocumentId()!=null || invoice.getPaperInvoiceIssuedAt()!=null
                || invoice.getStatus()!=com.hunt.otziv.common_billing.model.CommonInvoiceStatus.READY)
            throw problem("Общий счёт изменился или требует сверки перед новым счётом");
        invoice.setInvoicePaymentMode(mode);
        invoice.setPaperInvoiceAutomationHold(false);
        invoice.setLastError(CommonInvoiceDeliveryService.PAYMENT_ROUTE_CHANGED_MESSAGE_PENDING);
        invoices.save(invoice);
        for(long order:memberIds(id)) orders.holdAutomation(order,false);
        delivery.queueAfterPaperCancellation(id);
    }
    @Transactional
    public void settle(long id,long amount,String payment) { settlement.applyVerifiedPaperBankPayment(id,amount,payment);synchronizeOrders(id); }
    @Transactional
    public void delivered(long id,String document) { delivery.markBankPaperDocumentsDelivered(id,document);synchronizeOrders(id); }
    @Transactional
    public void lockForDelivery(long id) {delivery.lockedInvoiceAfterStandalonePaymentPrelude(id);}
    private void synchronizeOrders(long id) {
        for(long order:jdbc.queryForList("SELECT order_id FROM common_invoice_orders WHERE invoice_id=? AND active_membership=1",Long.class,id)) automation.synchronizeOrder(order);
    }
    @Transactional(readOnly=true)
    public StaffContext staffContext(long id) {
        var source=jdbc.queryForMap("SELECT a.manager_id,i.title FROM common_invoices i JOIN common_billing_accounts a ON a.account_id=i.account_id WHERE i.invoice_id=?",id);
        var ids=jdbc.queryForList("SELECT order_id FROM common_invoice_orders WHERE invoice_id=? AND active_membership=1 ORDER BY order_id",Long.class,id);
        var managers=new java.util.LinkedHashSet<Long>();
        if(source.get("manager_id") instanceof Number manager) managers.add(manager.longValue());
        else for(long order:ids) {Long manager=orders.staffContext(order).managerId();if(manager!=null) managers.add(manager);}
        return new StaffContext(java.util.List.copyOf(managers),ids,java.util.Objects.toString(source.get("title"),"Общий счёт"));
    }
    private static ResponseStatusException problem(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
}
