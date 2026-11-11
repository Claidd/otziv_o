package com.hunt.otziv.payments.api;

import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/** Authorized operations performed by the order-payment owner during a bank handover. */
public interface OrderRecipientRoutes {
    UnpaidBankObservation.Source lockedSource(long orderId, Long linkId, Authentication actor);
    PaymentRouteChangeResponse changeNow(long orderId, PaymentRouteChangeRequest request, Authentication actor);
    PaymentRouteChangeResponse changeMethodNow(long orderId, PaymentRouteChangeRequest request, Authentication actor);
    /** @param chosenProfileId the person a manager named while the bank link was still open, or null */
    PaymentRouteChangeResponse replace(UnpaidBankObservation.Source expected, String bankStatus, boolean publish, Authentication actor,
            Long chosenProfileId);
    /** @param chosenProfileId the person a manager named while the bank link was still open, or null */
    PaymentRouteChangeResponse replaceMethod(UnpaidBankObservation.Source expected, String bankStatus, boolean publish,
            com.hunt.otziv.payments.dto.PaymentRouteChangeTarget target, Authentication actor, Long chosenProfileId);
    void reconcile(long linkId);

    class SourceChanged extends ResponseStatusException {
        public SourceChanged(String reason) { super(HttpStatus.CONFLICT, reason); }
    }
}
