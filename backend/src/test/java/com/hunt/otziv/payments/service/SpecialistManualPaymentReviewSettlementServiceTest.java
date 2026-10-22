package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecialistManualPaymentReviewSettlementServiceTest {

    @Mock private SpecialistManualPaymentReviewRepository reviews;
    @Mock private ManualCardPaymentWorkflow manualPayments;
    @Mock private SpecialistPaymentReviewDirectory users;
    @Mock private PaymentIssueReminderService issues;
    @Mock private ContractorPaymentTargetAccessPolicy targetAccess;
    @Mock private SpecialistManualPaymentReviewService reviewReconciliation;

    @Test
    void noTelegramAcceptanceCannotAutoCreditAfterRestartAndDeadline() {
        SpecialistManualPaymentReview review = dueWaiting();
        review.setNotifiedAt(null);
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));

        service().settle(101L);

        verify(manualPayments, never()).confirmSpecialistReviewPayment(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals(SpecialistManualPaymentReviewStatus.WAITING, review.getStatus());
    }

    @Test
    void disputeWinsBeforeClaimAndBlocksAutoCredit() {
        SpecialistManualPaymentReview review = dueWaiting();
        review.setStatus(SpecialistManualPaymentReviewStatus.DISPUTED);
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));

        service().settle(101L);

        verify(manualPayments, never()).confirmSpecialistReviewPayment(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedNoticeAndElapsedWindowUsesFrozenRecipientExactlyOnce() {
        SpecialistManualPaymentReview review = dueWaiting();
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(users.activeActor(17L)).thenReturn(Optional.of(new SpecialistPaymentReviewDirectory.Actor(
                "manager", List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))));

        service().settle(101L);

        assertEquals(SpecialistManualPaymentReviewStatus.CONFIRMED, review.getStatus());
        verify(manualPayments).confirmSpecialistReviewPayment(
                eq(101L), eq(71L), eq(140_000L), any(), eq(""), eq("manager"), any(), any(),
                eq(27L), eq("profile:27"));
        verify(issues).resolveOrderIssue(7L,
                SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, 101L);
        service().settle(101L);
        verify(manualPayments).confirmSpecialistReviewPayment(
                eq(101L), eq(71L), eq(140_000L), any(), eq(""), eq("manager"), any(), any(),
                eq(27L), eq("profile:27"));
    }

    @Test
    void disputedReceiptNeedsExactAmountAndOwnerVerification() {
        SpecialistManualPaymentReview review = dueWaiting();
        review.setStatus(SpecialistManualPaymentReviewStatus.DISPUTED);
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        var admin = new UsernamePasswordAuthenticationToken("admin", null);

        assertThrows(ResponseStatusException.class, () -> service().verifyReceived(
                101L, true, true, 139_999L, "Выписка сверена", admin));
        verify(manualPayments, never()).confirmSpecialistReviewPayment(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        service().verifyReceived(101L, true, true, 140_000L, "Выписка сверена", admin);

        assertEquals(SpecialistManualPaymentReviewStatus.CONFIRMED, review.getStatus());
        assertEquals("ADMIN_VERIFIED_RECEIPT", review.getResolutionKind());
        verify(manualPayments).confirmSpecialistReviewPayment(
                eq(101L), eq(71L), eq(140_000L), any(), eq(""), eq("admin"), eq(admin), any(),
                eq(27L), eq("profile:27"));
    }

    @Test
    void bankPaymentWinningDuringManualSettlementClosesReviewWithoutManualError() {
        SpecialistManualPaymentReview review = dueWaiting();
        when(reviews.lockById(101L)).thenReturn(Optional.of(review));
        when(users.activeActor(17L)).thenReturn(Optional.of(new SpecialistPaymentReviewDirectory.Actor(
                "manager", List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))));
        org.mockito.Mockito.doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT))
                .when(manualPayments).confirmSpecialistReviewPayment(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(reviewReconciliation.supersedeOnConfirmedBankPayment(101L)).thenReturn(true);

        service().settle(101L);

        verify(reviewReconciliation).supersedeOnConfirmedBankPayment(101L);
        verify(issues, never()).notifyOrderIssueAfterCommit(any(), any(), any(), any(), any());
    }

    private SpecialistManualPaymentReviewSettlementService service() {
        return new SpecialistManualPaymentReviewSettlementService(reviews,
                new PaymentLinkTransactionExecutor(), manualPayments, users, issues, targetAccess,
                reviewReconciliation);
    }

    private SpecialistManualPaymentReview dueWaiting() {
        SpecialistManualPaymentReview review = new SpecialistManualPaymentReview();
        review.setId(101L); review.setOrderId(7L); review.setPaymentLinkId(71L);
        review.setAmountKopecks(140_000L); review.setRecipientProfileId(27L);
        review.setRecipientKey("profile:27"); review.setRecipientUserId(37L);
        review.setRequestedByUserId(17L); review.setRequestedBy("manager");
        review.setReason("Проверенный чек"); review.setReceiptUrl("");
        review.setNotifiedAt(LocalDateTime.now().minusHours(25));
        review.setDeadlineAt(LocalDateTime.now().minusHours(1));
        review.setStatus(SpecialistManualPaymentReviewStatus.WAITING);
        return review;
    }
}
