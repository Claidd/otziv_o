package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Atomically removes a disputed, unpaid order from an unsent invoice and archives it. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceDisputedOrderArchiveWorkflow {

    private final CommonInvoiceOrderRepository invoiceOrderRepository;
    private final CommonInvoiceMembershipWorkflow membershipWorkflow;
    private final CommonInvoiceManualPaymentWorkflow manualPaymentWorkflow;
    private final OrderStatusTransitionService statusTransitionService;

    @Transactional
    public CommonInvoiceDetailsResponse archive(Long invoiceId, Long orderId) {
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (item.getInvoice() == null || !invoiceId.equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }
        ensureCanArchive(item);
        membershipWorkflow.detachOrder(invoiceId, orderId);
        // detachOrder locks and refreshes the invoice and order. Check again in that same transaction.
        ensureNoPaymentState(item);
        if (invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(orderId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ остался в активном общем счете");
        }
        try {
            if (!statusTransitionService.changeStatusForOrder(orderId, "Архив")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось архивировать заказ");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось архивировать спорный заказ", exception);
        }
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    private void ensureCanArchive(CommonInvoiceOrder item) {
        CommonInvoice invoice = item.getInvoice();
        ensureNoPaymentState(item);
        if (!item.isActiveMembership() || invoice == null
                || (invoice.getStatus() != CommonInvoiceStatus.COLLECTING
                    && invoice.getStatus() != CommonInvoiceStatus.READY)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Заказ или общий счет уже имеет платежное состояние; требуется ручной разбор конфликта");
        }
    }

    private void ensureNoPaymentState(CommonInvoiceOrder item) {
        CommonInvoice invoice = item.getInvoice();
        Order order = item.getOrder();
        if (item.isPaid() || item.isUnpaid() || order == null || order.isComplete()
                || order.getPayDay() != null || invoice == null || invoice.getSentAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Заказ или общий счет уже имеет платежное состояние; требуется ручной разбор конфликта");
        }
    }
}
