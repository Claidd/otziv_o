package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.p_products.api.PaymentAttentionOrderReader;
import com.hunt.otziv.payments.dto.BankAttentionItemResponse;
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
import java.util.List;
import org.junit.jupiter.api.Test;

class BankAttentionServiceTest {

    private final OwnerManualCardPaymentApprovalRepository approvals = mock(OwnerManualCardPaymentApprovalRepository.class);
    private final SpecialistManualPaymentReviewRepository reviews = mock(SpecialistManualPaymentReviewRepository.class);
    private final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
    private final PaymentAttentionOrderReader orders = mock(PaymentAttentionOrderReader.class);
    private final BankAttentionService service = new BankAttentionService(approvals, reviews, links, orders);

    @Test
    void everyWaitingMatterIsListedOnceOldestFirstWithItsOrder() {
        LocalDateTime now = LocalDateTime.now();
        OwnerManualCardPaymentApproval approval = new OwnerManualCardPaymentApproval();
        approval.setId(45L);
        approval.setOrderId(26710L);
        approval.setPaymentLinkId(8800L);
        approval.setAmountKopecks(180_000L);
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.PENDING);
        approval.setRequestedAt(now.minusDays(8));
        SpecialistManualPaymentReview review = new SpecialistManualPaymentReview();
        review.setId(119L);
        review.setOrderId(27145L);
        review.setPaymentLinkId(8644L);
        review.setAmountKopecks(120_000L);
        review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
        review.setRequestedAt(now.minusDays(21));
        review.setLastError("Активный или неоднозначный платеж Точки нельзя автоматически закрыть");
        PaymentLink reported = new PaymentLink();
        reported.setId(8700L);
        reported.setStatus(PaymentLinkStatus.MANUAL_REPORTED);
        reported.setAmountKopecks(50_000L);
        reported.setManualReportedAt(now.minusDays(2));
        when(approvals.findByStatusOrderByRequestedAtAscIdAsc(any(), any())).thenReturn(List.of(approval));
        when(reviews.findByStatusInAndResolvedAtIsNullOrderByRequestedAtAscIdAsc(anyCollection(), any()))
                .thenReturn(List.of(review));
        when(links.findAttentionLinks(anyCollection(), any())).thenReturn(List.of());
        when(links.findClientReportedBefore(any(), any())).thenReturn(List.of(reported));
        when(orders.labels(any())).thenReturn(java.util.Map.of(26710L, label(26710L, "Напоминание", "Компания")));

        var response = service.attention();

        assertThat(response.truncated()).isFalse();
        assertThat(response.items()).extracting(BankAttentionItemResponse::kind)
                .containsExactly("SPECIALIST_REVIEW", "OWNER_APPROVAL", "CLIENT_REPORTED");
        BankAttentionItemResponse ownerRequest = response.items().get(1);
        assertThat(ownerRequest.approvalId()).isEqualTo(45L);
        assertThat(ownerRequest.companyTitle()).isEqualTo("Компания");
        assertThat(ownerRequest.orderStatus()).isEqualTo("Напоминание");
        assertThat(ownerRequest.amountKopecks()).isEqualTo(180_000L);
        assertThat(response.items().getFirst().detail()).startsWith("Активный или неоднозначный платеж Точки");
    }

    @Test
    void aRequestOfAnAlreadyPaidOrderSaysItWillCloseByItself() {
        OwnerManualCardPaymentApproval approval = new OwnerManualCardPaymentApproval();
        approval.setId(10L);
        approval.setOrderId(25326L);
        approval.setPaymentLinkId(7000L);
        approval.setAmountKopecks(100_000L);
        approval.setRequestedAt(LocalDateTime.now().minusDays(70));
        when(approvals.findByStatusOrderByRequestedAtAscIdAsc(any(), any())).thenReturn(List.of(approval));
        when(orders.labels(any())).thenReturn(java.util.Map.of(25326L, label(25326L, "Оплачено", "Кафе")));

        var item = service.attention().items().getFirst();

        assertThat(item.detail()).contains("закроется автоматически");
    }

    private static PaymentAttentionOrderReader.OrderLabel label(long id, String status, String company) {
        return new PaymentAttentionOrderReader.OrderLabel(id, company, status);
    }
}
