package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.payments.api.BadReviewBankRepricing;
import com.hunt.otziv.payments.api.UnpaidBankObservation.*;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.tochka.service.TochkaPaymentOperationMapper.MappedPayment;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderBadReviewBankRepricing implements BadReviewBankRepricing {
    private final PaymentLinkRepository links;
    private final OrderPaymentLinkWorkflow orderLinks;
    private final PaymentInvoiceRetryScheduler messages;
    private final PaymentLinkSettlementService settlement;
    private final ContractorPaymentLiveRoutingService reserves;

    public List<Long> due() { return links.dueBadReviewBankChecks(LocalDateTime.now()); }

    public Source claim(long id) {
        var link = orderLinks.lockForBadReviewBankCheck(id);
        if (link == null || link.getBadReviewBankCheckAt() == null || link.getBadReviewBankCheckAt().isAfter(LocalDateTime.now())) return null;
        var source = PaymentRouteReplacementWorkflow.repricingSource(link);
        if (source == null) {
            link.setBadReviewBankCheckAt(null);
            if (link.getPaidAt() == null && link.getConfirmedAmountKopecks() == null) {
                link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
                link.setLastError("Банковская операция изменилась во время пересчёта. Требуется сверка.");
            }
            return null;
        }
        link.setBadReviewBankCheckAt(LocalDateTime.now().plusMinutes(1));
        return source;
    }

    public void finish(Source source, Result result) {
        var link = orderLinks.lockForBadReviewBankCheck(source.sourceId());
        if (link == null || link.getBadReviewBankCheckAt() == null
                || !source.equals(PaymentRouteReplacementWorkflow.repricingSource(link))) return;
        if (result.outcome() == Outcome.CLOSED) {
            link.setStatus(PaymentLinkStatus.EXPIRED);
            link.setProviderTerminalStatus(result.status());
            link.setLastError(null);
            link.setBadReviewBankCheckAt(null);
            reserves.releaseClosedPaymentLink(link);
            messages.scheduleRepricedBadReviewInvoice(link.getOrder());
        } else if (result.outcome() == Outcome.FINANCIAL) {
            if (result.mappedStatus() != null && !"HISTORY".equals(result.status()))
                settlement.applyTochkaMappedStatus(link, new MappedPayment(result.mappedStatus(), result.paymentMethod(),
                        result.status(), result.providerPaymentType()), link.getPaymentProfile().isTestMode());
            else if ("CONFIRMED".equals(result.status()))
                settlement.applyBankStatus(link, result.status(), true, "0");
            else {
                link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
                link.setLastError("Банк сообщил о движении денег по прежней сумме: " + result.status());
            }
            link.setBadReviewBankCheckAt(null);
        }
    }
}
