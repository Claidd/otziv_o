package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class PaymentLinkExpirySweepTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

    private final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentLinkLifecycleService lifecycle = mock(PaymentLinkLifecycleService.class);
    private final ManualPaymentTaskReceiptIntegrationService taskReceipts = mock(ManualPaymentTaskReceiptIntegrationService.class);
    private final ContractorPaymentLiveRoutingService contractorRouting = mock(ContractorPaymentLiveRoutingService.class);
    private final SpecialistManualPaymentReviewGuard reviewGuard = mock(SpecialistManualPaymentReviewGuard.class);
    private final OrderPaymentLinkWorkflow workflow = new OrderPaymentLinkWorkflow(
            mock(PaymentLinkAmountPolicy.class), lifecycle, mock(PaymentLinkPreparationWorkflow.class),
            mock(BankObservationApplicationService.class), mock(PaymentLinkPresenter.class),
            mock(CommonInvoiceRouteSelector.class), mock(PaymentBankObservationService.class),
            links, orders, taskReceipts, contractorRouting, mock(PaymentLinkTransactionExecutor.class), reviewGuard);
    private PaymentLink link;

    @BeforeEach
    void setUp() {
        Order order = new Order();
        order.setId(26710L);
        link = new PaymentLink();
        link.setId(9001L);
        link.setOrder(order);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setExpiresAt(NOW.minusDays(1));
        when(links.findOrderIdById(9001L)).thenReturn(Optional.of(26710L));
        when(orders.findByIdForCounterUpdate(26710L)).thenReturn(Optional.of(order));
        when(links.findByIdForUpdate(9001L)).thenReturn(Optional.of(link));
        when(lifecycle.canRetireStaleLink(link)).thenReturn(true);
    }

    @Test
    void aPastDueUnstartedLinkExpiresAndReleasesItsReserve() {
        assertThat(workflow.expirePastDueLink(9001L, NOW)).isTrue();

        assertThat(link.getStatus()).isEqualTo(PaymentLinkStatus.EXPIRED);
        assertThat(link.getLastError()).isEqualTo("Срок действия платежной ссылки истек");
        verify(taskReceipts).release(link, "Срок действия платежной ссылки истек");
        verify(links).save(link);
        verify(contractorRouting).releaseClosedPaymentLink(link);
    }

    @Test
    void aLinkWithAClientReportOrAPendingCheckIsLeftForAPerson() {
        link.setManualReportedAt(NOW.minusDays(3));
        assertThat(workflow.expirePastDueLink(9001L, NOW)).isFalse();

        link.setManualReportedAt(null);
        when(reviewGuard.hasPendingReview(26710L)).thenReturn(true);
        assertThat(workflow.expirePastDueLink(9001L, NOW)).isFalse();

        assertThat(link.getStatus()).isEqualTo(PaymentLinkStatus.CREATED);
        verify(links, never()).save(any());
        verify(contractorRouting, never()).releaseClosedPaymentLink(any());
    }

    @Test
    void aStartedBankOperationOrAnUnexpiredTermIsNotTouched() {
        when(lifecycle.canRetireStaleLink(link)).thenReturn(false);
        assertThat(workflow.expirePastDueLink(9001L, NOW)).isFalse();

        when(lifecycle.canRetireStaleLink(link)).thenReturn(true);
        link.setExpiresAt(NOW.plusHours(1));
        assertThat(workflow.expirePastDueLink(9001L, NOW)).isFalse();

        link.setExpiresAt(NOW.minusDays(1));
        link.setBankCancelNonce("cancel-in-flight");
        assertThat(workflow.expirePastDueLink(9001L, NOW)).isFalse();

        assertThat(link.getStatus()).isEqualTo(PaymentLinkStatus.CREATED);
        verify(taskReceipts, never()).release(any(PaymentLink.class), anyString());
    }

    @Test
    void theSweepContinuesAfterAFailureAndWrapsAroundAtTheEnd() {
        PaymentLinkRepository repository = mock(PaymentLinkRepository.class);
        OrderPaymentLinkWorkflow expiry = mock(OrderPaymentLinkWorkflow.class);
        when(repository.findPastDueUnstartedLinkIds(eq(0L), any(), eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(5L, 6L));
        when(expiry.expirePastDueLink(5L, NOW)).thenThrow(new IllegalStateException("locked"));
        when(expiry.expirePastDueLink(6L, NOW)).thenReturn(true);
        PaymentLinkExpirySweep sweep = new PaymentLinkExpirySweep(repository, expiry);

        assertThat(sweep.run(NOW)).isEqualTo(1);
        // A short batch means the end was reached: the next pass starts from the beginning again.
        assertThat(sweep.run(NOW)).isEqualTo(1);
        verify(repository, never()).findPastDueUnstartedLinkIds(eq(6L), any(), any(), any(Pageable.class));
        verify(expiry, never()).expirePastDueLink(anyLong(), eq(NOW.plusDays(1)));
    }
}
