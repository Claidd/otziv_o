package com.hunt.otziv.payments.service;

import com.hunt.otziv.p_products.api.PaymentAttentionOrderReader;
import com.hunt.otziv.p_products.api.PaymentAttentionOrderReader.OrderLabel;
import com.hunt.otziv.payments.dto.BankAttentionItemResponse;
import com.hunt.otziv.payments.dto.BankAttentionResponse;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Требует решения»: one list of every payment matter waiting for a person, oldest first, so nothing hides
 * behind a counter or below the journal. Read-only; each entry points to the order and the action place.
 */
@Service
@RequiredArgsConstructor
public class BankAttentionService {

    static final int LIMIT_PER_KIND = 100;
    static final int CLIENT_REPORTED_AFTER_HOURS = 24;

    private final OwnerManualCardPaymentApprovalRepository approvals;
    private final SpecialistManualPaymentReviewRepository reviews;
    private final PaymentLinkRepository links;
    private final PaymentAttentionOrderReader orders;

    @Transactional(readOnly = true)
    public BankAttentionResponse attention() {
        LocalDateTime now = LocalDateTime.now();
        PageRequest page = PageRequest.of(0, LIMIT_PER_KIND);
        List<OwnerManualCardPaymentApproval> pendingApprovals = approvals.findByStatusOrderByRequestedAtAscIdAsc(
                OwnerManualCardPaymentApprovalStatus.PENDING, page);
        List<SpecialistManualPaymentReview> stoppedReviews = reviews.findByStatusInAndResolvedAtIsNullOrderByRequestedAtAscIdAsc(
                EnumSet.of(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION, SpecialistManualPaymentReviewStatus.DISPUTED), page);
        List<PaymentLink> reconciliation = links.findAttentionLinks(
                EnumSet.of(PaymentLinkStatus.NEEDS_RECONCILIATION, PaymentLinkStatus.AMOUNT_MISMATCH), page);
        List<PaymentLink> reported = links.findClientReportedBefore(
                now.minusHours(CLIENT_REPORTED_AFTER_HOURS), page);
        boolean truncated = pendingApprovals.size() >= LIMIT_PER_KIND || stoppedReviews.size() >= LIMIT_PER_KIND
                || reconciliation.size() >= LIMIT_PER_KIND || reported.size() >= LIMIT_PER_KIND;

        Set<Long> orderIds = new LinkedHashSet<>();
        pendingApprovals.forEach(approval -> orderIds.add(approval.getOrderId()));
        stoppedReviews.forEach(review -> orderIds.add(review.getOrderId()));
        reconciliation.forEach(link -> orderIds.add(link.getOrderId()));
        reported.forEach(link -> orderIds.add(link.getOrderId()));
        orderIds.remove(null);
        Map<Long, OrderLabel> byId = orders.labels(orderIds);

        List<BankAttentionItemResponse> items = new ArrayList<>();
        for (OwnerManualCardPaymentApproval approval : pendingApprovals) {
            OrderLabel order = label(byId, approval.getOrderId());
            boolean paid = order != null && "Оплачено".equals(order.statusTitle());
            items.add(item("OWNER_APPROVAL", "Запрос владельцу: подтвердить поступление",
                    paid ? "Заказ уже оплачен: запрос закроется автоматически при следующей сверке."
                            : "Менеджер сообщил о переводе владельцу. Подтвердите поступление в Telegram или отклоните запрос.",
                    order, approval.getOrderId(), approval.getPaymentLinkId(), approval.getId(), null,
                    approval.getAmountKopecks(), approval.getRequestedAt()));
        }
        for (SpecialistManualPaymentReview review : stoppedReviews) {
            boolean disputed = review.getStatus() == SpecialistManualPaymentReviewStatus.DISPUTED;
            items.add(item("SPECIALIST_REVIEW",
                    disputed ? "Получатель оспорил ручную оплату" : "Ручная оплата остановлена",
                    blankToDefault(review.getLastError(), disputed
                            ? "Сверьте выписку получателя и решите, было ли поступление."
                            : "Автоматическое зачисление остановлено; нужна сверка."),
                    label(byId, review.getOrderId()), review.getOrderId(), review.getPaymentLinkId(), null, review.getId(),
                    review.getAmountKopecks(), review.getRequestedAt()));
        }
        for (PaymentLink link : reconciliation) {
            items.add(item("LINK_RECONCILIATION",
                    link.getStatus() == PaymentLinkStatus.AMOUNT_MISMATCH ? "Оплата пришла не на ту сумму" : "Платёж требует сверки",
                    blankToDefault(link.getLastError(), "Проверьте операцию в банке и журнале платежей."),
                    label(byId, link.getOrderId()), link.getOrderId(), link.getId(), null, null,
                    link.getAmountKopecks(), link.getUpdatedAt() != null ? link.getUpdatedAt() : link.getCreatedAt()));
        }
        for (PaymentLink link : reported) {
            items.add(item("CLIENT_REPORTED", "Клиент сообщил об оплате",
                    "Сверьте поступление с выпиской получателя и отметьте оплату или отсутствие перевода.",
                    label(byId, link.getOrderId()), link.getOrderId(), link.getId(), null, null,
                    link.getAmountKopecks(), link.getManualReportedAt()));
        }
        items.sort(Comparator.comparing(BankAttentionItemResponse::since, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BankAttentionItemResponse::kind)
                .thenComparing(item -> Objects.requireNonNullElse(item.orderId(), 0L)));
        return new BankAttentionResponse(List.copyOf(items), truncated, now);
    }

    private static BankAttentionItemResponse item(String kind, String title, String detail, OrderLabel order,
            Long orderId, Long paymentLinkId, Long approvalId, Long reviewId, long amountKopecks, LocalDateTime since) {
        return new BankAttentionItemResponse(kind, title, detail, orderId,
                order == null ? null : order.companyTitle(), order == null ? null : order.statusTitle(),
                paymentLinkId, approvalId, reviewId, amountKopecks, since);
    }

    /** A link without an order (or an order that left the live table) simply has no label. */
    private static OrderLabel label(Map<Long, OrderLabel> labels, Long orderId) {
        return orderId == null || labels == null ? null : labels.get(orderId);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
