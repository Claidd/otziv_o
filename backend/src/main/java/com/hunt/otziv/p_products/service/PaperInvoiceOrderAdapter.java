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
    public long companyId(long id) { return jdbc.queryForObject("SELECT order_company FROM orders WHERE order_id=?",Long.class,id); }
    @Transactional
    public void lockForDelivery(long id) {orders.findByIdForCounterUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Заказ не найден"));}
    @Transactional
    public void markDelivered(long id) {
        try {transitions.markBankPaperInvoiceDelivered(id);automation.synchronizeOrder(id);}
        catch(RuntimeException e) {throw e;}
        catch(Exception e) {throw new IllegalStateException("Не удалось завершить отправку счёта",e);}
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
