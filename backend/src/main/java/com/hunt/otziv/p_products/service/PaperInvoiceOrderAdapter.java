package com.hunt.otziv.p_products.service;

import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.p_products.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class PaperInvoiceOrderAdapter implements PaperInvoiceOrderData {
    private final OrderRepository orders;
    private final JdbcTemplate jdbc;
    private final com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold recovery;
    private final com.hunt.otziv.p_products.status.service.OrderStatusTransitionService transitions;
    private final com.hunt.otziv.client_messages.api.PaperInvoiceAutomation automation;
    private final OrderStatusService statuses;
    public java.util.List<AutomaticOrder> automaticCandidates(long after,int limit) {
        return jdbc.query("""
            SELECT o.order_id,o.order_company,o.paper_billing_payer_choice_id FROM orders o JOIN order_statuses s ON s.order_status_id=o.order_status
            JOIN companies c ON c.company_id=o.order_company
            WHERE o.order_id>? AND o.invoice_payment_mode='OWNER_PAPER_INVOICE' AND c.paper_billing_automatic=1
            AND o.order_amount>0 AND o.order_counter>=o.order_amount AND o.paper_invoice_automation_hold=0 AND o.paper_invoice_review_hold=0
            AND s.order_status_title IN ('Опубликовано','Ожидает общего счета','Выставлен счет','Напоминание')
            ORDER BY o.order_id LIMIT ?
            """,(rs,n)->new AutomaticOrder(rs.getLong(1),rs.getLong(2),rs.getString(3)),after,Math.min(100,Math.max(1,limit)));
    }
    public AutomaticOrder automaticOrder(long id) {
        return jdbc.query("SELECT order_id,order_company,paper_billing_payer_choice_id FROM orders WHERE order_id=? AND paper_invoice_automation_hold=0 AND paper_invoice_review_hold=0 AND invoice_payment_mode='OWNER_PAPER_INVOICE'",
                (rs,n)->new AutomaticOrder(rs.getLong(1),rs.getLong(2),rs.getString(3)),id).stream().findFirst().orElse(null);
    }
    @Transactional
    public void rememberPayer(long id,String choice) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        order.useBankPaperInvoicePaymentMode();
        order.setPaperBillingPayerChoiceId(choice);orders.saveAndFlush(order);
    }
    @Transactional
    public void holdForPaymentReview(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElse(null);
        if(order==null) return; // The cancelled document outlives deletion/archival of its source.
        order.setPaperInvoiceReviewHold(true);order.setPaperInvoiceAutomationHold(true);orders.saveAndFlush(order);automation.synchronizeOrder(id);
    }
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public void requireUnpaidForCancellation(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        if("Оплачено".equals(order.getStatus().getTitle()) || (order.isComplete() && order.getPayDay()!=null))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Заказ уже оплачен; отмена документов запрещена");
    }
    @Transactional
    public void holdAutomation(long id,boolean hold) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        if(!hold && order.isPaperInvoiceReviewHold()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Есть несверенное поступление по отменённому счёту");
        order.setPaperInvoiceAutomationHold(hold);orders.saveAndFlush(order);automation.synchronizeOrder(id);
    }
    @Transactional
    public void cancelled(long id,boolean commonMember) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        if("Оплачено".equals(order.getStatus().getTitle())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Заказ уже оплачен");
        if(java.util.Set.of("Выставлен счет","Напоминание","Не оплачено").contains(order.getStatus().getTitle())) {
            order.setStatus(statuses.getOrderStatusByTitle(commonMember?"Ожидает общего счета":"Опубликовано"));
            order.setStatusChangedAt(java.time.LocalDateTime.now());
            order.setClientMessageGeneration(Math.addExact(order.getClientMessageGeneration(),1));
        }
        order.setPaperInvoiceAutomationHold(true);orders.saveAndFlush(order);automation.synchronizeOrder(id);
    }
    @Transactional
    public void resumeOrdinaryInvoice(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        requireUnpaidForCancellation(id);
        if(order.isPaperInvoiceReviewHold() || !order.isPaperInvoiceAutomationHold()
                || !order.isBankPaperInvoice() || !"Опубликовано".equals(order.getStatus().getTitle()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Заказ изменился или требует сверки перед новым счётом");
        order.useAutomaticInvoicePaymentMode();
        order.setPaperInvoiceAutomationHold(false);
        order.setStatusChangedAt(java.time.LocalDateTime.now());
        order.setClientMessageGeneration(Math.addExact(order.getClientMessageGeneration(),1));
        orders.saveAndFlush(order);
        automation.scheduleOrdinaryInvoice(id);
    }
    public long companyId(long id) { return jdbc.queryForObject("SELECT order_company FROM orders WHERE order_id=?",Long.class,id); }
    @Transactional
    public void resumeCommonOrdinaryInvoice(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        requireUnpaidForCancellation(id);
        if(order.isPaperInvoiceReviewHold() || !order.isPaperInvoiceAutomationHold()
                || !order.isBankPaperInvoice()
                || !java.util.Set.of("Опубликовано","Ожидает общего счета").contains(order.getStatus().getTitle()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Выбранный заказ изменился или требует сверки");
        order.useAutomaticInvoicePaymentMode();
        order.setPaperInvoiceAutomationHold(false);
        order.setStatus(statuses.getOrderStatusByTitle("Опубликовано"));
        order.setStatusChangedAt(java.time.LocalDateTime.now());
        order.setClientMessageGeneration(Math.addExact(order.getClientMessageGeneration(),1));
        orders.saveAndFlush(order);
        automation.scheduleOrdinaryInvoice(id);
    }
    @Transactional
    public void lockForDelivery(long id) {orders.findByIdForCounterUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Заказ не найден"));}
    @Transactional
    public void markDelivered(long id) {
        try {transitions.markBankPaperInvoiceDelivered(id);automation.synchronizeOrder(id);}
        catch(RuntimeException e) {throw e;}
        catch(Exception e) {throw new IllegalStateException("Не удалось завершить отправку счёта",e);}
    }
    @Transactional
    public void markCommonDelivered(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        if("Оплачено".equals(order.getStatus().getTitle())) return;
        try {transitions.changeStatusForCommonBillingOrder(id,"Выставлен счет");automation.synchronizeOrder(id);}
        catch(RuntimeException failure) {throw failure;}
        catch(Exception failure) {throw new IllegalStateException("Не удалось завершить отправку выбранных заказов",failure);}
    }
    @Transactional(readOnly=true)
    public StaffContext staffContext(long id) {
        var order=orders.findByIdForOrderDto(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Заказ не найден"));
        return new StaffContext(order.notificationManagerId(),order.notificationCompanyTitle(),order.notificationFilialTitle());
    }
    @Transactional
    public void lockCompletedUnpaid(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Заказ не найден"));
        if(order.getAmount()<=0 || order.getCounter()<order.getAmount() || "Оплачено".equals(order.getStatus().getTitle()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Счёт и акт можно выпускать только по выполненному неоплаченному заказу");
        if(recovery.shouldPauseActiveOrder(id))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Есть незавершённые задачи восстановления. Счёт и акт можно выпустить после их завершения");
    }
}
