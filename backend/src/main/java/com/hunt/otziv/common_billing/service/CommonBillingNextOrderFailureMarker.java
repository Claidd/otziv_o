package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonBillingNextOrderFailureMarker {

    private static final int MAX_ERROR_LENGTH = 512;

    private final CommonInvoiceOrderRepository invoiceOrderRepository;
    private final CommonInvoiceRepository invoiceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAttentionForSourceOrder(Order sourceOrder, Long requestId, Throwable cause) {
        Long orderId = sourceOrder == null ? null : sourceOrder.getId();
        if (orderId == null) {
            return;
        }

        try {
            invoiceOrderRepository.findByOrderIdWithInvoice(orderId).ifPresent(item ->
                    markAttention(item, requestId, cause)
            );
        } catch (RuntimeException e) {
            log.warn("Не удалось пометить общий счет как требующий внимания после сбоя следующего заказа {}", orderId, e);
        }
    }

    private void markAttention(CommonInvoiceOrder item, Long requestId, Throwable cause) {
        CommonInvoice invoice = item.getInvoice();
        if (invoice == null) {
            return;
        }

        if (invoice.getStatus() != CommonInvoiceStatus.PAID
                && invoice.getStatus() != CommonInvoiceStatus.NEEDS_ATTENTION) {
            return;
        }

        invoice.setStatus(CommonInvoiceStatus.NEEDS_ATTENTION);
        invoice.setLastError(limit(
                "next_order_failed: платеж закрыт, но следующий заказ не создался"
                        + " по заявке #" + (requestId == null ? "-" : requestId)
                        + " для заказа #" + item.getOrder().getId()
                        + ": " + concise(cause),
                MAX_ERROR_LENGTH
        ));
        invoiceRepository.save(invoice);
    }

    private String concise(Throwable throwable) {
        if (throwable == null) {
            return "неизвестная ошибка";
        }
        Throwable current = throwable;
        Throwable last = throwable;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        String message = last.getMessage();
        if (message == null || message.isBlank()) {
            return last.getClass().getSimpleName();
        }
        return last.getClass().getSimpleName() + ": " + message.trim();
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }
}
