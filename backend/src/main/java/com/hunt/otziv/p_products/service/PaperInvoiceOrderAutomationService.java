package com.hunt.otziv.p_products.service;

import com.hunt.otziv.p_products.api.PaperInvoiceOrderAutomation;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.client_messages.api.PaperInvoiceAutomation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** No status-transition/review service graph is needed just to resume payment reminders. */
@Service @RequiredArgsConstructor
public class PaperInvoiceOrderAutomationService implements PaperInvoiceOrderAutomation {
    private final OrderRepository orders;
    private final ObjectProvider<PaperInvoiceAutomation> automation;
    @Transactional(propagation=Propagation.MANDATORY)
    public void resumeAfterRouteChange(long id) {
        var order=orders.findByIdForCounterUpdate(id).orElseThrow();
        if(order.isPaperInvoiceReviewHold()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Есть несверенное поступление по отменённому счёту");
        order.setPaperInvoiceAutomationHold(false);orders.saveAndFlush(order);
        automation.getObject().synchronizeOrder(id);
    }
}
