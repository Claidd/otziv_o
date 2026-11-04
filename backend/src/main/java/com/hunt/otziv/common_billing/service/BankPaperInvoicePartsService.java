package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.api.*;
import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import com.hunt.otziv.common_billing.dto.ManualPaymentConfirmationRequest;
import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.client_messages.api.PaperInvoiceAutomation;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class BankPaperInvoicePartsService implements BankPaperInvoiceParts {
    private final JdbcTemplate jdbc;
    private final CommonInvoiceDeliveryService delivery;
    private final CommonInvoicePaymentRouteWorkflow routes;
    private final CommonInvoiceRepository invoices;
    private final CommonInvoiceOrderRepository items;
    private final CommonInvoiceManualPaymentWorkflow manual;
    private final CommonInvoiceSettlementService settlement;
    private final BankPaperInvoiceAdapter legacy;
    private final PaperInvoiceOrderData orders;
    private final PaperInvoiceAutomation automation;
    private final com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold recovery;

    public long parent(long part) {return jdbc.queryForObject("SELECT invoice_id FROM common_invoice_paper_parts WHERE part_id=?",Long.class,part);}
    @Transactional(readOnly=true)
    public List<Long> partIds(long invoice) {positions(invoice);return jdbc.queryForList("SELECT part_id FROM common_invoice_paper_parts WHERE invoice_id=? ORDER BY part_id",Long.class,invoice);}
    @Transactional(readOnly=true)
    public List<Long> historyInvoiceIds(long invoice) {positions(invoice);return jdbc.queryForList("SELECT old.invoice_id FROM common_invoices current_invoice JOIN common_invoices old ON old.account_id=current_invoice.account_id WHERE current_invoice.invoice_id=? ORDER BY old.invoice_id DESC LIMIT 100",Long.class,invoice);}
    public LocalDateTime nextReminder(long part) {
        // With automatic reminders switched off the next date used to be NULL, which ended the
        // reminder chain for good. Keep a near re-check instead; the send path asks the
        // autoresponder for permission and postpones while reminders are off.
        LocalDateTime next = delivery.nextAutomaticPaymentReminderAt(LocalDateTime.now());
        return next != null ? next : LocalDateTime.now().plusHours(6);
    }
    public boolean remindersAllowed(long part) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT p.paid_at IS NULL AND p.cancelled_at IS NULL AND NOT p.automation_hold
              AND NOT i.paper_invoice_review_hold AND i.status IN ('INVOICED','REMINDER','PARTIALLY_PAID')
            FROM common_invoice_paper_parts p JOIN common_invoices i ON i.invoice_id=p.invoice_id WHERE p.part_id=?
            """,Boolean.class,part));
    }
    public Long parentForOrder(long order) {
        return jdbc.queryForList("SELECT p.invoice_id FROM common_invoice_orders p JOIN common_invoices i ON i.invoice_id=p.invoice_id WHERE p.order_id=? AND p.active_membership=1 AND i.status NOT IN ('PAID','ARCHIVED','DISABLED','BAN')",Long.class,order).stream().findFirst().orElse(null);
    }
    @Transactional(readOnly=true)
    public List<Position> positions(long invoice) {
        var value=invoices.findById(invoice).orElseThrow(()->problem("Общий счёт не найден"));
        delivery.ensureCommonInvoiceVisibleForCurrentUser(value);
        return currentPositions(invoice);
    }
    /** Internal financial verification is driven by a verified bank receipt, not a manager browser session. */
    private List<Position> currentPositions(long invoice) {
        return jdbc.queryForList("""
            SELECT o.order_id,o.amount_kopecks,o.ready,o.paid,c.company_id,c.company_title,
            IF(o.ordinary_after_paper_cancel,'ORDINARY',COALESCE(p.document_id,IF(po.part_id IS NULL,NULL,'PREPARING'))) AS document_id
            FROM common_invoice_orders o JOIN orders r ON r.order_id=o.order_id JOIN companies c ON c.company_id=r.order_company
            LEFT JOIN common_invoice_paper_part_orders po ON po.active_order_id=o.order_id
            LEFT JOIN common_invoice_paper_parts p ON p.part_id=po.part_id
            WHERE o.invoice_id=? AND o.active_membership=1 ORDER BY c.company_id,o.order_id
            """,invoice).stream().map(this::position).toList();
    }
    private Position position(Map<String,Object> r) {return new Position(((Number)r.get("order_id")).longValue(),((Number)r.get("company_id")).longValue(),
            (String)r.get("company_title"),((Number)r.get("amount_kopecks")).longValue(),flag(r.get("ready")),flag(r.get("paid")),(String)r.get("document_id"));}
    private boolean flag(Object v) {return Boolean.TRUE.equals(v) || "1".equals(Objects.toString(v));}
    @Transactional(readOnly=true)
    public List<Long> historyCompanyIds(long invoice) {
        positions(invoice); // Retain account visibility for historical suggestions.
        return jdbc.queryForList("""
            SELECT DISTINCT r.order_company FROM common_invoices current_invoice
            JOIN common_invoices old_invoice ON old_invoice.account_id=current_invoice.account_id
            JOIN common_invoice_orders o ON o.invoice_id=old_invoice.invoice_id JOIN orders r ON r.order_id=o.order_id
            WHERE current_invoice.invoice_id=? ORDER BY r.order_company LIMIT 200
            """,Long.class,invoice);
    }
    @Transactional
    public void configure(long invoice,Principal actor) {
        var value=invoices.findById(invoice).orElseThrow();
        delivery.ensureCommonInvoiceVisibleForCurrentUser(value);
        if(!CommonInvoiceRouteState.isOwnerPaperInvoice(value)) routes.configureBankPaperMode(invoice,actor);
    }
    @Transactional
    public long create(long invoice,List<Long> selected,String key) {
        var value=delivery.lockedInvoiceAfterStandalonePaymentPrelude(invoice).invoice();
        delivery.ensureCommonInvoiceVisibleForCurrentUser(value);
        if(!CommonInvoiceRouteState.isOwnerPaperInvoice(value) || value.isPaperInvoiceReviewHold()
                || !Set.of(CommonInvoiceStatus.COLLECTING,CommonInvoiceStatus.READY,CommonInvoiceStatus.INVOICED,CommonInvoiceStatus.REMINDER,CommonInvoiceStatus.PARTIALLY_PAID).contains(value.getStatus()))
            throw problem("Общий счёт ещё не готов или требует сверки");
        var replay=jdbc.queryForList("SELECT part_id FROM common_invoice_paper_parts WHERE invoice_id=? AND request_key=?",Long.class,invoice,key);
        var ids=normalize(selected);
        if(!replay.isEmpty()) {
            long part=replay.getFirst();
            if(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM common_invoice_paper_parts WHERE part_id=?",Boolean.class,part)) throw problem("Этот запрос уже отменён. Откройте новую форму счёта");
            if(!members(part).stream().map(Position::orderId).sorted().toList().equals(ids)) throw problem("Повторный запрос содержит другой состав заказов");
            return part;
        }
        if(value.getPaperBankDocumentId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM common_invoice_paper_parts WHERE invoice_id=?",Long.class,invoice)==0)
            throw problem("Уже выставлен единый банковский счёт. Сначала подтвердите его отмену");
        var all=positions(invoice);
        var picked=all.stream().filter(p->ids.contains(p.orderId())).toList();
        if(picked.size()!=ids.size() || picked.stream().anyMatch(p->!p.ready() || p.paid() || p.documentId()!=null || p.amountKopecks()<=0))
            throw problem("Выберите готовые неоплаченные заказы, ещё не включённые в банковский счёт");
        for(var p:picked) if(recovery.shouldPauseActiveOrder(p.orderId())) throw problem("Есть незавершённое восстановление выбранного заказа");
        long amount=picked.stream().mapToLong(Position::amountKopecks).reduce(0,Math::addExact);
        String hash=hash(picked);
        var keyHolder=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO common_invoice_paper_parts(invoice_id,request_key,amount_kopecks,composition_hash) VALUES(?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,invoice);statement.setString(2,key);statement.setLong(3,amount);statement.setString(4,hash);return statement;},keyHolder);
        long part=keyHolder.getKey().longValue();
        for(var p:picked) jdbc.update("INSERT INTO common_invoice_paper_part_orders(part_id,order_id,company_id,company_title,amount_kopecks) VALUES(?,?,?,?,?)",part,p.orderId(),p.companyId(),p.companyTitle(),p.amountKopecks());
        return part;
    }
    private List<Long> normalize(List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>200 || ids.stream().anyMatch(i->i==null || i<=0) || ids.stream().distinct().count()!=ids.size()) throw problem("Выберите заказы для счёта");
        return ids.stream().sorted().toList();
    }
    public List<Position> members(long part) {
        return jdbc.queryForList("SELECT order_id,company_id,company_title,amount_kopecks,1 AS ready,0 AS paid,NULL AS document_id FROM common_invoice_paper_part_orders WHERE part_id=? ORDER BY order_id",part).stream().map(this::position).toList();
    }
    public BankPaperInvoices.Snapshot read(long part) {
        var parent=legacy.read(parent(part));
        return new BankPaperInvoices.Snapshot(parent.companyId(),jdbc.queryForObject("SELECT amount_kopecks FROM common_invoice_paper_parts WHERE part_id=?",Long.class,part));
    }
    @Transactional public void lock(long part) {delivery.lockedInvoiceAfterStandalonePaymentPrelude(parent(part));}
    @Transactional public BankPaperInvoices.Snapshot prepare(long part) {
        lock(part); requireUnpaid(part);
        if(jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM common_invoice_paper_parts WHERE part_id=?",Boolean.class,part)) throw problem("Этот комплект отменён");
        return read(part);
    }
    public String composition(long part) {
        // Re-read live membership, company and amounts. Any drift fences settlement.
        var ids=members(part).stream().map(Position::orderId).toList();
        var current=currentPositions(parent(part)).stream().filter(p->ids.contains(p.orderId())).toList();
        return hash(current);
    }
    private String hash(List<Position> rows) {
        String text=rows.stream().sorted(Comparator.comparingLong(Position::orderId)).map(p->p.orderId()+":"+p.companyId()+":"+p.amountKopecks()).collect(java.util.stream.Collectors.joining(";"));
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception failure) {throw new IllegalStateException(failure);}
    }
    @Transactional public void bind(long part,String doc) {
        var value=delivery.lockedInvoiceAfterStandalonePaymentPrelude(parent(part)).invoice();
        jdbc.update("UPDATE common_invoice_paper_parts SET document_id=? WHERE part_id=? AND document_id IS NULL",doc,part);
        if(value.getPaperBankDocumentId()==null) value.setPaperBankDocumentId(doc);
        value.setPaperInvoiceAutomationHold(true);invoices.save(value);
        for(var p:members(part)) orders.holdAutomation(p.orderId(),false);
    }
    @Transactional public void delivered(long part,String document) {
        var value=delivery.lockedInvoiceAfterStandalonePaymentPrelude(parent(part)).invoice();
        jdbc.update("UPDATE common_invoice_paper_parts SET delivered_at=COALESCE(delivered_at,NOW(6)) WHERE part_id=? AND document_id=?",part,document);
        if(value.getStatus()!=CommonInvoiceStatus.PAID && value.getStatus()!=CommonInvoiceStatus.PARTIALLY_PAID) value.setStatus(CommonInvoiceStatus.INVOICED);
        if(value.getSentAt()==null) value.setSentAt(LocalDateTime.now());
        if(value.getPaperInvoiceIssuedAt()==null) value.setPaperInvoiceIssuedAt(LocalDateTime.now());
        // Per-document reminders are owned by paper_billing. Never send the whole parent amount again.
        value.setNextReminderAt(null);invoices.save(value);
        for(var p:members(part)) {orders.markCommonDelivered(p.orderId());automation.synchronizeOrder(p.orderId());}
    }
    @Transactional public void requireUnpaid(long part) {
        var value=delivery.lockedInvoiceAfterStandalonePaymentPrelude(parent(part)).invoice();
        if(value.isPaperInvoiceReviewHold()) throw problem("Есть несверенное поступление; нужна сверка");
        var row=jdbc.queryForMap("SELECT * FROM common_invoice_paper_parts WHERE part_id=? FOR UPDATE",part);
        if(row.get("paid_at")!=null) throw problem("Выбранный банковский счёт уже оплачен");
        var ids=members(part).stream().map(Position::orderId).toList();
        if(items.findByInvoiceIdWithOrders(value.getId()).stream().filter(i->ids.contains(i.orderId())).anyMatch(CommonInvoiceOrder::isPaid)) throw problem("Часть выбранных заказов уже оплачена");
        for(long id:ids) orders.requireUnpaidForCancellation(id);
    }
    @Transactional public void settle(long part,long amount,String payment) {
        var value=delivery.lockedInvoiceAfterStandalonePaymentPrelude(parent(part)).invoice();
        requireUnpaid(part);
        var row=jdbc.queryForMap("SELECT * FROM common_invoice_paper_parts WHERE part_id=? FOR UPDATE",part);
        if(row.get("cancelled_at")!=null || !CommonInvoiceRouteState.isOwnerPaperInvoice(value) || amount!=((Number)row.get("amount_kopecks")).longValue()
                || !composition(part).equals(row.get("composition_hash"))) throw problem("Состав банковского счёта изменился");
        var all=items.findByInvoiceIdWithOrders(value.getId()).stream().filter(CommonInvoiceOrder::isActiveMembership).toList();
        var ids=members(part).stream().map(Position::orderId).toList();
        var chosen=all.stream().filter(i->ids.contains(i.orderId()) && i.isActiveMembership()).toList();
        if(chosen.size()!=ids.size() || chosen.stream().anyMatch(i->!i.isReady() || i.isPaid())) throw problem("Выбранные позиции изменились");
        manual.applyManualPaymentEvidence(value,chosen,new ManualPaymentConfirmationRequest("Точка: банковский перевод "+payment,""),()->"tochka-bank");
        for(var item:chosen) {
            try {settlement.closeBankPaperPositionAsPaid(item);}
            catch(ResponseStatusException failure) {throw failure;}
            catch(Exception failure) {throw new IllegalStateException("Не удалось применить оплату выбранного заказа",failure);}
            item.setPaid(true);item.setUnpaid(false);item.setPaidAt(LocalDateTime.now());item.setPaymentMethod("OWNER_PAPER_INVOICE");
        }
        items.saveAll(chosen);value.setPaymentMethod("OWNER_PAPER_INVOICE");
        settlement.recalculateInvoice(value,all);
        if(all.stream().filter(CommonInvoiceOrder::isActiveMembership).allMatch(CommonInvoiceOrder::isPaid)) settlement.closePaidInvoice(value,all);
        else {value.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);value.setNextReminderAt(null);invoices.save(value);}
        jdbc.update("UPDATE common_invoice_paper_parts SET paid_at=NOW(6) WHERE part_id=?",part);
        for(var p:members(part)) automation.synchronizeOrder(p.orderId());
    }
    @Transactional public void hold(long part,boolean held) {
        lock(part);jdbc.update("UPDATE common_invoice_paper_parts SET automation_hold=? WHERE part_id=?",held,part);
        for(var p:members(part)) orders.holdAutomation(p.orderId(),held);
    }
    @Transactional public void review(long part) {legacy.holdForPaymentReview(parent(part));}
    @Transactional public void cancelled(long part,String document) {
        var value=delivery.lockedInvoiceAfterStandalonePaymentPrelude(parent(part)).invoice();requireUnpaid(part);
        jdbc.update("UPDATE common_invoice_paper_parts SET cancelled_at=NOW(6),automation_hold=1 WHERE part_id=? AND document_id=?",part,document);
        jdbc.update("UPDATE common_invoice_paper_part_orders SET released_at=NOW(6) WHERE part_id=?",part);
        for(var p:members(part)) orders.cancelled(p.orderId(),true);
        var remaining=jdbc.queryForList("SELECT document_id FROM common_invoice_paper_parts WHERE invoice_id=? AND cancelled_at IS NULL AND document_id IS NOT NULL ORDER BY part_id",String.class,value.getId());
        value.setPaperBankDocumentId(remaining.isEmpty()?null:remaining.getFirst());
        if(value.getPaidKopecks()>0) value.setStatus(CommonInvoiceStatus.PARTIALLY_PAID);
        else if(remaining.isEmpty()) {value.setStatus(CommonInvoiceStatus.READY);value.setPaperInvoiceIssuedAt(null);value.setSentAt(null);}
        value.setNextReminderAt(null);invoices.save(value);
    }
    @Transactional
    public void resumeOrdinaryInvoice(long part) {
        var parent=delivery.lockedInvoiceAfterStandalonePaymentPrelude(parent(part)).invoice();
        requireUnpaid(part);
        var cancelled=jdbc.queryForMap("SELECT * FROM common_invoice_paper_parts WHERE part_id=? FOR UPDATE",part);
        if(cancelled.get("cancelled_at")==null || !composition(part).equals(cancelled.get("composition_hash")))
            throw problem("Отмена комплекта или его состав не подтверждены");
        var memberIds=members(part).stream().map(Position::orderId).toList();
        var current=currentPositions(parent.getId()).stream().filter(p->memberIds.contains(p.orderId())).toList();
        if(current.size()!=memberIds.size() || current.stream().anyMatch(p->p.paid() || !p.ready() || p.documentId()!=null))
            throw problem("Выбранные заказы уже оплачены, перевыставлены или изменились");
        var selected=items.findByInvoiceIdWithOrders(parent.getId()).stream()
                .filter(CommonInvoiceOrder::isActiveMembership).filter(item->memberIds.contains(item.orderId())).toList();
        if(selected.size()!=memberIds.size()) throw problem("Состав общего счёта изменился");
        selected.forEach(item->item.setOrdinaryAfterPaperCancel(true));
        items.saveAll(selected);
        // Membership stays active: each ordinary receipt contributes to the same
        // total and successor orders remain deferred until the whole invoice is paid.
        for(long order:memberIds) orders.resumeCommonOrdinaryInvoice(order);
        parent.setNextReminderAt(null);invoices.save(parent);
    }

    public BankPaperInvoices.StaffContext staffContext(long part) {
        var context=legacy.staffContext(parent(part));
        return new BankPaperInvoices.StaffContext(context.managerIds(),members(part).stream().map(Position::orderId).toList(),context.title());
    }
    private static ResponseStatusException problem(String text) {return new ResponseStatusException(HttpStatus.CONFLICT,text);}
}
