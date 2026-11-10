package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.api.OrderRecipientRoutes;
import com.hunt.otziv.payments.api.UnpaidBankObservation.Source;
import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderRecipientRouteAdapter implements OrderRecipientRoutes {
    private final PaymentRouteReplacementWorkflow routes;
    private final PaymentRouteChangeService changes;
    private final PaymentLinkService payments;

    @Override
    public Source lockedSource(long orderId, Long linkId, Authentication actor) {
        return PaymentRouteReplacementWorkflow.recipientSource(routes.lockRecipientChangeSource(orderId, linkId, actor));
    }

    @Override
    public PaymentRouteChangeResponse changeNow(long orderId, PaymentRouteChangeRequest request, Authentication actor) {
        return changes.change(orderId, request, actor, true);
    }

    @Override
    public PaymentRouteChangeResponse changeMethodNow(long orderId, PaymentRouteChangeRequest request, Authentication actor) {
        return changes.change(orderId,request,actor);
    }

    @Override
    public PaymentRouteChangeResponse replace(Source expected, String bankStatus, boolean publish, Authentication actor) {
        return replace(expected,bankStatus,publish,PaymentRouteChangeTarget.EMPLOYEE_REQUISITES,true,actor);
    }

    @Override
    public PaymentRouteChangeResponse replaceMethod(Source expected, String bankStatus, boolean publish, PaymentRouteChangeTarget target, Authentication actor) {
        return replace(expected,bankStatus,publish,target,false,actor);
    }

    private PaymentRouteChangeResponse replace(Source expected,String bankStatus,boolean publish,PaymentRouteChangeTarget target,boolean different,Authentication actor) {
        var link = routes.lockRecipientChangeSource(expected.targetId(), expected.sourceId(), actor);
        if (!expected.equals(PaymentRouteReplacementWorkflow.recipientSource(link))) {
            throw new SourceChanged("Ссылка или состояние заказа изменились");
        }
        if (publish) {
            if ("TOCHKA".equals(expected.provider()) && "CREATED".equals(bankStatus)) link.setObserveReplacedBank(true);
            else link.setProviderTerminalStatus(bankStatus);
        }
        var result = routes.replaceBankRoute(expected.targetId(), expected.sourceId(),target,different,actor);
        if (!publish) return null;
        return changes.notifyReplacement(expected.targetId(), new PaymentLinkService.PaymentRouteReplacement(
                result.previousPaymentLinkId(), result.paymentLinkId(), result.target(), result.response()));
    }

    @Override
    public void reconcile(long linkId) {
        payments.reconcileBankLink(linkId, LocalDateTime.now());
    }
}
