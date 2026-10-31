package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.api.DisputedOrderArchive;
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
    private final DisputedOrderArchive disputedOrderArchive;

    @Transactional
    public CommonInvoiceDetailsResponse archive(Long invoiceId, Long orderId) {
        CommonInvoiceOrder item = invoiceOrderRepository.findByOrderIdWithInvoice(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден в общем счете"));
        if (item.getInvoice() == null || !invoiceId.equals(item.getInvoice().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ относится к другому общему счету");
        }
        ensureCanArchive(item, orderId);
        membershipWorkflow.detachOrderForArchive(invoiceId, orderId);
        // Keep the current review status for the archive transition; restoring an old
        // "Новый" status here would make the real archive command reject the order.
        ensureNoPaymentState(item, orderId);
        if (invoiceOrderRepository.findByOrder_IdAndActiveMembershipTrue(orderId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заказ остался в активном общем счете");
        }
        try {
            if (!disputedOrderArchive.archive(orderId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось архивировать заказ");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось архивировать спорный заказ", exception);
        }
        return manualPaymentWorkflow.invoiceAfterOrderPrelude(invoiceId);
    }

    private void ensureCanArchive(CommonInvoiceOrder item, Long orderId) {
        CommonInvoice invoice = item.getInvoice();
        ensureNoPaymentState(item, orderId);
        if (!item.isActiveMembership() || invoice == null
                || !CommonInvoiceCompositionService.canChangeStatus(invoice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Заказ или общий счет уже имеет платежное состояние; требуется ручной разбор конфликта");
        }
    }

    private void ensureNoPaymentState(CommonInvoiceOrder item, Long orderId) {
        CommonInvoice invoice = item.getInvoice();
        if (item.isPaid() || item.isUnpaid() || disputedOrderArchive.hasPaymentState(orderId)
                || invoice == null || invoice.getSentAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Заказ или общий счет уже имеет платежное состояние; требуется ручной разбор конфликта");
        }
    }
}
