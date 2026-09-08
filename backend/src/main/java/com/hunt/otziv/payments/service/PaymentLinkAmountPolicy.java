package com.hunt.otziv.payments.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Authoritative standalone amount: order price plus completed bad-review work, rounded once to kopecks. */
@Service
@RequiredArgsConstructor
public class PaymentLinkAmountPolicy {
    private final BadReviewTaskService badReviewTaskService;

    long currentAmountKopecks(PaymentLink link) {
        if (link == null || link.getOrder() == null || link.getOrder().getId() == null) {
            return link == null ? 0 : link.getAmountKopecks();
        }
        return amountKopecks(payableSum(link.getOrder()));
    }

    BigDecimal payableSum(Order order) {
        BigDecimal baseSum = order.getSum() == null ? BigDecimal.ZERO : order.getSum();
        BadReviewTaskSummary summary = badReviewTaskService.getSummaryForOrder(order.getId());
        BigDecimal extra = summary == null ? BigDecimal.ZERO : summary.doneSum();
        return baseSum.add(extra);
    }

    long amountKopecks(BigDecimal amount) {
        return amount
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValue();
    }
}
