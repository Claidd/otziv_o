package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.*;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class OwnerManualCardPaymentApprovalReconciliationServiceTest {
    private final OwnerManualCardPaymentApprovalRepository approvals = mock(OwnerManualCardPaymentApprovalRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
    private final ManagerAccessService access = mock(ManagerAccessService.class);
    private final ManualCardPaymentReviewNotificationService notifications = mock(ManualCardPaymentReviewNotificationService.class);
    private final OwnerManualCardPaymentApprovalReconciliationService service =
            new OwnerManualCardPaymentApprovalReconciliationService(approvals, orders, links, access, notifications);
    private final Authentication actor = new UsernamePasswordAuthenticationToken("alex", null, List.of());
    private OwnerManualCardPaymentApproval approval;
    private Order order;
    private PaymentLink original;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(26061L);
        order.setStatus(OrderStatus.builder().title("Оплачено").build());
        approval = new OwnerManualCardPaymentApproval();
        approval.setId(2L);
        approval.setOrderId(order.getId());
        approval.setPaymentLinkId(7410L);
        approval.setAmountKopecks(60000);
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.PENDING);
        original = link(7410L, PaymentLinkStatus.CANCELED, 60000);
        when(approvals.findByIdForUpdate(2L)).thenReturn(Optional.of(approval));
        when(orders.findByIdForCounterUpdate(order.getId())).thenReturn(Optional.of(order));
        when(links.findByIdForUpdate(7410L)).thenReturn(Optional.of(original));
    }

    @Test
    void closesStaleRequestWithoutCreditingMoneyOrInventingOwnerConfirmation() {
        when(links.findByOrderIdAndStatusInForUpdate(order.getId(), List.of(PaymentLinkStatus.CONFIRMED)))
                .thenReturn(List.of(link(7438L, PaymentLinkStatus.CONFIRMED, 60000)));

        var result = service.closeSuperseded(2L, actor);

        assertThat(result.status()).isEqualTo("SUPERSEDED");
        assertThat(approval.getApprovedAt()).isNull();
        assertThat(approval.getApprovedByUserId()).isNull();
        assertThat(approval.getLastError()).contains("7410", "7438", "alex", "Повторного зачисления не было");
        verify(access).requireOrderAccess(order.getId(), actor);
        verify(notifications).closeOwnerApprovalReminders(2L);
        verify(links, never()).save(any());
        verify(orders, never()).save(any());
    }

    @Test
    void partialPaymentIsNotEnoughToCloseTheRequest() {
        PaymentLink replacement = link(7438L, PaymentLinkStatus.CONFIRMED, 60000);
        replacement.setConfirmedAmountKopecks(30000L);
        when(links.findByOrderIdAndStatusInForUpdate(order.getId(), List.of(PaymentLinkStatus.CONFIRMED)))
                .thenReturn(List.of(replacement));

        assertThatThrownBy(() -> service.closeSuperseded(2L, actor)).isInstanceOf(ResponseStatusException.class);

        assertThat(approval.getStatus()).isEqualTo(OwnerManualCardPaymentApprovalStatus.PENDING);
        verify(approvals, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test
    void activeOriginalPaymentCannotBeClosedAsObsolete() {
        original.setStatus(PaymentLinkStatus.CREATED);

        assertThatThrownBy(() -> service.closeSuperseded(2L, actor)).isInstanceOf(ResponseStatusException.class);

        assertThat(approval.getStatus()).isEqualTo(OwnerManualCardPaymentApprovalStatus.PENDING);
        verify(approvals, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test
    void completedRequestIsIdempotentAndDoesNotTouchPayments() {
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.SUPERSEDED);

        assertThat(service.closeSuperseded(2L, actor).alreadyClosed()).isTrue();

        verify(links, never()).findByIdForUpdate(any());
        verify(links, never()).save(any());
        verify(approvals, never()).save(any());
    }

    @Test
    void unavailableOrderAccessPreventsClosure() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND))
                .when(access).requireOrderAccess(order.getId(), actor);

        assertThatThrownBy(() -> service.closeSuperseded(2L, actor)).isInstanceOf(ResponseStatusException.class);

        verify(orders, never()).findByIdForCounterUpdate(any());
        verify(approvals, never()).save(any());
        verifyNoInteractions(notifications);
    }

    private PaymentLink link(long id, PaymentLinkStatus status, long amount) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setOrder(order);
        link.setStatus(status);
        link.setAmountKopecks(amount);
        return link;
    }
}
