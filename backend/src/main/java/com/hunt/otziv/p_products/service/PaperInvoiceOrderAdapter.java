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
    public long companyId(long id) { return jdbc.queryForObject("SELECT order_company FROM orders WHERE order_id=?",Long.class,id); }
    @Transactional
    public void lockCompletedUnpaid(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Заказ не найден"));
        if(order.getAmount()<=0 || order.getCounter()<order.getAmount() || "Оплачено".equals(order.getStatus().getTitle()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Счёт и акт можно выпускать только по выполненному неоплаченному заказу");
        if(recovery.shouldPauseActiveOrder(id))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Есть незавершённые задачи восстановления. Счёт и акт можно выпустить после их завершения");
    }
}
