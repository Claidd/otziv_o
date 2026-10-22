package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.RevokePendingOwnerApprovalRequest;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class OwnerManualCardPaymentApprovalRevocationServiceTest {
    private final OwnerManualCardPaymentApprovalRepository approvals = mock(OwnerManualCardPaymentApprovalRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentLinkRepository links = mock(PaymentLinkRepository.class);
    private final SpecialistManualPaymentReviewRepository reviews = mock(SpecialistManualPaymentReviewRepository.class);
    private final ManagerAccessService access = mock(ManagerAccessService.class);
    private final ManualCardRoutePolicy routePolicy = mock(ManualCardRoutePolicy.class);
    private final OrderPaymentIntegrityService integrity = mock(OrderPaymentIntegrityService.class);
    private final ContractorActualPaymentAttributionService attribution = mock(ContractorActualPaymentAttributionService.class);
    private final ManualCardPaymentReviewNotificationService notifications = mock(ManualCardPaymentReviewNotificationService.class);
    private final BusinessAuditService audit = mock(BusinessAuditService.class);
    private final OwnerManualCardPaymentApprovalRevocationService service =
            new OwnerManualCardPaymentApprovalRevocationService(approvals, orders, links, reviews,
                    access, routePolicy, integrity, attribution, notifications, audit);
    private final Authentication actor = new UsernamePasswordAuthenticationToken("alex", null, List.of());
    private final RevokePendingOwnerApprovalRequest request =
            new RevokePendingOwnerApprovalRequest(27407L, 8544L, "Клиент перечислил Виктории");
    private OwnerManualCardPaymentApproval approval;
    private PaymentLink link;
    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(27407L);
        link = new PaymentLink();
        link.setId(8544L);
        link.setOrder(order);
        link.setAmountKopecks(100000);
        link.setStatus(PaymentLinkStatus.CREATED);
        link.setManualActualRecipientFrozenAt(LocalDateTime.now());
        link.setManualActualRecipientType(ContractorRecipientType.OWNER);
        link.setManualActualCashDestinationKind(ContractorCashDestinationKind.OWNER);
        approval = new OwnerManualCardPaymentApproval();
        approval.setId(39L);
        approval.setOrderId(order.getId());
        approval.setPaymentLinkId(link.getId());
        approval.setAmountKopecks(link.getAmountKopecks());
        approval.setRecipientKey("OWNER");
        approval.setRecipientType(ContractorRecipientType.OWNER);
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.PENDING);
        var binding = mock(OwnerManualCardPaymentApprovalRepository.ApprovalBinding.class);
        when(binding.getOrderId()).thenReturn(order.getId());
        when(binding.getPaymentLinkId()).thenReturn(link.getId());
        when(approvals.findBindingById(39L)).thenReturn(Optional.of(binding));
        when(orders.findByIdForCounterUpdate(order.getId())).thenReturn(Optional.of(order));
        when(links.findByOrderIdForUpdate(order.getId())).thenReturn(List.of(link));
        when(approvals.findByIdForUpdate(39L)).thenReturn(Optional.of(approval));
        when(routePolicy.isUnstartedCreatedBankRoute(link)).thenReturn(true);
        when(reviews.lockByPaymentLinkId(link.getId())).thenReturn(Optional.empty());
        when(approvals.saveAndFlush(approval)).thenReturn(approval);
        when(links.saveAndFlush(link)).thenReturn(link);
    }

    @Test
    void revokesBeforeManagerRetryWithoutCreditingOrChangingOrder() {
        var result = service.revokePending(39L, request, actor);

        assertThat(result.status()).isEqualTo("REVOKED");
        assertThat(result.alreadyRevoked()).isFalse();
        assertThat(approval.getStatus()).isEqualTo(OwnerManualCardPaymentApprovalStatus.REVOKED);
        assertThat(approval.getRevokedBy()).isEqualTo("alex");
        assertThat(approval.getRevocationReason()).isEqualTo(request.reason());
        verify(attribution).clearRevokedPaymentLinkRecipientIntent(link);
        verify(notifications).closeOwnerApprovalReminders(39L);
        verify(audit).recordRequiredInCurrentTransaction(any(), eq("OWNER_APPROVAL_REVOKED"),
                eq("OWNER_MANUAL_CARD_APPROVAL"), eq(39L), eq(27407L), isNull(), any(), any(), eq(request.reason()));
        verify(orders, never()).save(any());
        var lockOrder = inOrder(orders, links, approvals);
        lockOrder.verify(orders).findByIdForCounterUpdate(27407L);
        lockOrder.verify(links).findByOrderIdForUpdate(27407L);
        lockOrder.verify(approvals).findByIdForUpdate(39L);
    }

    @Test
    void callbackAttemptFencesRevocation() {
        approval.setAttemptCount(1);

        assertThatThrownBy(() -> service.revokePending(39L, request, actor))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(attribution, notifications, audit);
        verify(links, never()).saveAndFlush(any());
    }

    @Test
    void providerAttemptFencesRevocation() {
        link.setTbankOrderId("bank-order");

        assertThatThrownBy(() -> service.revokePending(39L, request, actor))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(attribution, notifications, audit);
    }

    @Test
    void existingSettlementFencesRevocation() {
        link.setConfirmedAmountKopecks(100000L);

        assertThatThrownBy(() -> service.revokePending(39L, request, actor))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(attribution, notifications, audit);
    }

    @Test
    void staleOrderOrLinkSelectionFencesRevocation() {
        var wrong = new RevokePendingOwnerApprovalRequest(27407L, 8545L, "Ошибка");

        assertThatThrownBy(() -> service.revokePending(39L, wrong, actor))
                .isInstanceOf(ResponseStatusException.class);
        verify(orders, never()).findByIdForCounterUpdate(any());
        verifyNoInteractions(attribution, notifications, audit);
    }

    @Test
    void repeatedRevocationIsReadOnly() {
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.REVOKED);

        assertThat(service.revokePending(39L, request, actor).alreadyRevoked()).isTrue();
        verifyNoInteractions(attribution, audit);
        verify(approvals, never()).saveAndFlush(any());
        verify(links, never()).saveAndFlush(any());
    }
}
