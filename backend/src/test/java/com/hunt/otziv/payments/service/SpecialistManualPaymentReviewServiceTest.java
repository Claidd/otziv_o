package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecialistManualPaymentReviewServiceTest {

    @Mock private SpecialistManualPaymentReviewRepository reviews;
    @Mock private PaymentLinkRepository links;
    @Mock private SpecialistPaymentReviewDirectory users;
    @Mock private ManagerAccessService managerAccess;
    @Mock private ManualCardPaymentWorkflow manualPayments;
    @Mock private PaymentInvoiceRetryScheduler invoiceMessages;
    @Mock private TelegramService telegram;
    @Mock private PaymentIssueReminderService issues;
    @Mock private ContractorPaymentTargetAccessPolicy targetAccess;
    @Mock private SpecialistManualPaymentReviewGuard reviewGuard;
    @Mock private ManualCardRoutePolicy manualRoutePolicy;
    @Mock private ManualPaymentConfirmationWorkflow manualConfirmation;
    @Mock private OrderPaymentIntegrityService orderPaymentIntegrity;
    @Spy private PaymentLinkTransactionExecutor transactions = new PaymentLinkTransactionExecutor();
    @InjectMocks private SpecialistManualPaymentReviewService service;

    @Test
    void managerReportStartsDeadlineWithoutCreditingAndFreezesRecipient() {
        Order order = new Order(); order.setId(7L);
        PaymentLink link = new PaymentLink(); link.setId(71L); link.setOrder(order);
        link.setAmountKopecks(140_000L);
        when(orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(7L)).thenReturn(Optional.of(false));
        when(links.findByIdForUpdate(71L)).thenReturn(Optional.of(link));
        when(users.activeUserIdByUsername("manager")).thenReturn(Optional.of(17L));
        when(reviews.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            link.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);
            link.setManualActualRecipientProfileId(27L);
            link.setManualActualRecipientUserId(37L);
            return null;
        }).when(manualPayments).prepareSpecialistReviewRecipient(
                eq(7L), eq(link), eq(27L),
                eq("profile:27"), eq("Менеджер проверил чек"), eq(""), eq("manager"));

        SpecialistManualPaymentReview review = service.request(7L, 71L, 140_000L,
                27L, "profile:27", "Менеджер проверил чек", "", "manager",
                new UsernamePasswordAuthenticationToken("manager", null));

        assertEquals(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY, review.getStatus());
        assertEquals(37L, review.getRecipientUserId());
        assertEquals(review.getRequestedAt().plusHours(24), review.getDeadlineAt());
        assertNotNull(review.getRequestedAt());
        verify(invoiceMessages).closePaymentAutomationForManualSettlement(eq(7L), any());
        verify(telegram, never()).sendPaymentReviewNoticeOnce(anyLong(), any(), any(), any());
    }

    @Test
    void recipientChangeCannotRestartClock() {
        SpecialistManualPaymentReview review = pending();
        review.setRecipientProfileId(27L);
        review.setRecipientKey("profile:27");
        when(orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(7L)).thenReturn(Optional.of(false));
        when(links.findByIdForUpdate(71L)).thenReturn(Optional.of(link()));
        when(reviews.lockByPaymentLinkId(71L)).thenReturn(Optional.of(review));

        assertThrows(ResponseStatusException.class, () -> service.request(7L, 71L,
                140_000L, 28L, "profile:28", "other", "", "manager",
                new UsernamePasswordAuthenticationToken("manager", null)));
        verify(reviews, never()).saveAndFlush(any());
    }

    @Test
    void telegramAcceptanceOpensDisputeWindowWithoutChangingDeadline() {
        SpecialistManualPaymentReview review = pending();
        review.setId(101L);
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(users.activePersonalTelegramChatId(37L)).thenReturn(Optional.of(999L));
        when(telegram.sendPaymentReviewNoticeOnce(eq(999L), any(), any(), eq("specialist-payment-dispute:101")))
                .thenReturn(true);
        LocalDateTime originalDeadline = review.getDeadlineAt();

        service.deliver(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.WAITING, review.getStatus());
        assertNotNull(review.getNotifiedAt());
        assertEquals(originalDeadline, review.getDeadlineAt());
        verify(issues).resolveOrderIssueInCurrentTransaction(
                SpecialistManualPaymentReviewService.DELIVERY_ISSUE, 101L);
    }

    @Test
    void telegramAcceptanceSurvivesMySqlMicrosecondRoundTrip() {
        SpecialistManualPaymentReview review = pending();
        review.setId(101L);
        when(reviews.lockById(101L)).thenAnswer(call -> {
            if (review.getLastAttemptAt() != null) {
                review.setLastAttemptAt(review.getLastAttemptAt().truncatedTo(ChronoUnit.MICROS));
            }
            return Optional.of(review);
        });
        when(users.activePersonalTelegramChatId(37L)).thenReturn(Optional.of(999L));
        when(telegram.sendPaymentReviewNoticeOnce(eq(999L), any(), any(), any())).thenReturn(true);

        service.deliver(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.WAITING, review.getStatus());
        assertNotNull(review.getNotifiedAt());
        assertEquals(review.getLastAttemptAt(),
                review.getLastAttemptAt().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void failedNotificationNeverEnablesAutomaticCredit() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(users.activePersonalTelegramChatId(37L)).thenReturn(Optional.of(999L));
        when(telegram.sendPaymentReviewNoticeOnce(eq(999L), any(), any(), any())).thenReturn(false);

        service.deliver(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY, review.getStatus());
        assertEquals(null, review.getNotifiedAt());
        assertNotNull(review.getLastAttemptAt());
        verify(issues).notifyOrderIssueAfterCommit(eq(7L),
                eq(SpecialistManualPaymentReviewService.DELIVERY_ISSUE), eq(101L), any(), any());
    }

    @Test
    void delayedNotificationDoesNotShortenSpecialistDisputeWindow() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setRequestedAt(LocalDateTime.now().minusMinutes(6));
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));

        service.deliver(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION, review.getStatus());
        verify(telegram, never()).sendPaymentReviewNoticeOnce(anyLong(), any(), any(), any());
    }

    @Test
    void unknownPriorTelegramAttemptCannotBeSentTwice() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setRequestedAt(LocalDateTime.now().minusMinutes(4));
        review.setLastAttemptAt(LocalDateTime.now().minusMinutes(2));
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));

        service.deliver(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY, review.getStatus());
        verify(telegram, never()).sendPaymentReviewNoticeOnce(anyLong(), any(), any(), any());
    }

    @Test
    void uncertainTelegramOutcomeRequiresManualReconciliationWithoutRetry() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(users.activePersonalTelegramChatId(37L)).thenReturn(Optional.of(999L));
        when(telegram.sendPaymentReviewNoticeOnce(eq(999L), any(), any(), any()))
                .thenThrow(new IllegalStateException("connection lost"));

        service.deliver(101L);
        service.deliver(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION, review.getStatus());
        assertEquals(1, review.getAttemptCount());
        verify(telegram).sendPaymentReviewNoticeOnce(eq(999L), any(), any(), any());
    }

    @Test
    void onlyAssignedSpecialistCanDisputeBeforeDeadline() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setStatus(SpecialistManualPaymentReviewStatus.WAITING);
        review.setNotifiedAt(LocalDateTime.now());
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(users.activePersonalTelegramChatId(37L)).thenReturn(Optional.of(999L));

        assertThrows(ResponseStatusException.class, () -> service.dispute(101L, 998L));
        assertEquals(SpecialistManualPaymentReviewStatus.WAITING, review.getStatus());
        service.dispute(101L, 999L);
        assertEquals(SpecialistManualPaymentReviewStatus.DISPUTED, review.getStatus());
        verify(issues).notifyOrderIssueAfterCommit(eq(7L),
                eq(SpecialistManualPaymentReviewService.DISPUTE_ISSUE), eq(101L), any(), any());
    }

    @Test
    void lateTelegramDeliveryRequiresManualReviewEvenWhenApiAccepted() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));

        service.deliver(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION, review.getStatus());
        assertTrue(review.getLastError().contains("24 часа"));
        verify(telegram, never()).sendPaymentReviewNoticeOnce(anyLong(), any(), any(), any());
    }

    @Test
    void verifiedAbsentExternalTransferDoesNotMutateBankPaymentRoute() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setStatus(SpecialistManualPaymentReviewStatus.DISPUTED);
        PaymentLink bankLink = link(); bankLink.setPaymentMethod(PaymentMethod.BANK_FORM);
        bankLink.setStatus(PaymentLinkStatus.CREATED);
        when(reviews.findById(101L)).thenReturn(Optional.of(review));
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(7L)).thenReturn(Optional.of(false));
        when(links.findByIdForUpdate(71L)).thenReturn(Optional.of(bankLink));

        service.markAbsent(101L, true, true, "Специалист проверил выписку; денег нет",
                new UsernamePasswordAuthenticationToken("admin", null));

        assertEquals(SpecialistManualPaymentReviewStatus.SUPERSEDED, review.getStatus());
        assertEquals("PAYMENT_ABSENT", review.getResolutionKind());
        assertEquals(PaymentLinkStatus.CREATED, bankLink.getStatus());
        verify(links, never()).save(any());
        verify(manualConfirmation, never()).closeManualAsUnpaid(any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    void bankHandoffReconcilesOnlyAfterManualCompletionAudit() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
        PaymentLink bankLink = link(); bankLink.setPaymentMethod(PaymentMethod.BANK_FORM);
        bankLink.setStatus(PaymentLinkStatus.CANCELED);
        bankLink.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);
        bankLink.setManualActualRecipientUserId(37L);
        bankLink.setManualActualRecipientProfileId(27L);
        review.setRecipientProfileId(27L);
        when(reviews.findById(101L)).thenReturn(Optional.of(review));
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(7L)).thenReturn(Optional.of(false));
        when(links.findByIdForUpdate(71L)).thenReturn(Optional.of(bankLink));
        when(manualRoutePolicy.isCompletedManualCardPayment(bankLink)).thenReturn(true);
        when(manualRoutePolicy.isSpecialistReviewRecipient(bankLink, 37L, 27L)).thenReturn(true);

        service.reconcileOperatorOutcome(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.CONFIRMED, review.getStatus());
    }

    @Test
    void confirmedProviderPaymentOnExactLinkSupersedesManualReview() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setStatus(SpecialistManualPaymentReviewStatus.WAITING);
        PaymentLink bankLink = link(); bankLink.setPaymentMethod(PaymentMethod.BANK_FORM);
        bankLink.setStatus(PaymentLinkStatus.CONFIRMED);
        bankLink.setConfirmedAmountKopecks(140_000L);
        bankLink.setPaidAt(LocalDateTime.now());
        bankLink.setTbankPaymentId("provider-payment-71");
        when(reviews.findById(101L)).thenReturn(Optional.of(review));
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(7L)).thenReturn(Optional.of(true));
        when(links.findByIdForUpdate(71L)).thenReturn(Optional.of(bankLink));

        assertTrue(service.supersedeOnConfirmedBankPayment(101L));

        assertEquals(SpecialistManualPaymentReviewStatus.SUPERSEDED, review.getStatus());
        assertEquals("BANK_PROVIDER_CONFIRMED", review.getResolutionKind());
        verify(issues).resolveOrderIssueInCurrentTransaction(
                SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, 101L);
        verify(manualPayments, never()).confirmSpecialistReviewPayment(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void providerPaymentWithDifferentAmountDoesNotResolveManualReview() {
        SpecialistManualPaymentReview review = pending(); review.setId(101L);
        review.setStatus(SpecialistManualPaymentReviewStatus.WAITING);
        PaymentLink bankLink = link(); bankLink.setPaymentMethod(PaymentMethod.BANK_FORM);
        bankLink.setStatus(PaymentLinkStatus.CONFIRMED);
        bankLink.setConfirmedAmountKopecks(120_000L);
        bankLink.setPaidAt(LocalDateTime.now());
        bankLink.setTbankPaymentId("provider-payment-71");
        when(reviews.findById(101L)).thenReturn(Optional.of(review));
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(7L)).thenReturn(Optional.of(false));
        when(links.findByIdForUpdate(71L)).thenReturn(Optional.of(bankLink));

        assertEquals(false, service.supersedeOnConfirmedBankPayment(101L));
        assertEquals(SpecialistManualPaymentReviewStatus.WAITING, review.getStatus());
        verify(issues, never()).resolveOrderIssueInCurrentTransaction(any(), any());
    }

    private Order order() { Order order = new Order(); order.setId(7L); return order; }

    private PaymentLink link() {
        PaymentLink link = new PaymentLink(); link.setId(71L); link.setOrder(order());
        link.setAmountKopecks(140_000L); return link;
    }

    private SpecialistManualPaymentReview pending() {
        SpecialistManualPaymentReview review = new SpecialistManualPaymentReview();
        review.setOrderId(7L); review.setPaymentLinkId(71L);
        review.setAmountKopecks(140_000L); review.setRecipientUserId(37L);
        review.setStatus(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY);
        review.setRequestedAt(LocalDateTime.now());
        review.setDeadlineAt(LocalDateTime.now().plusHours(24));
        return review;
    }

}
