package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpecialistManualPaymentReviewSettlementServiceTest {
    @Mock SpecialistManualPaymentReviewRepository reviews;
    @Mock ManualCardPaymentWorkflow manualPayments;
    @Mock SpecialistPaymentReviewDirectory users;
    @Mock PaymentIssueReminderService issues;
    @Mock ContractorPaymentTargetAccessPolicy targetAccess;
    @Mock SpecialistManualPaymentReviewService reconciliation;

    @ParameterizedTest @EnumSource(ContractorRecipientType.class)
    void managerMarkCreditsImmediatelyBeforeNotificationForEveryRecipientAndNeverTwice(ContractorRecipientType type) {
        var r = report(); r.setRecipientType(type.name());
        if (type == ContractorRecipientType.OWNER) { r.setRecipientProfileId(null); r.setRecipientUserId(null); r.setRecipientKey("OWNER"); }
        stub(r); activeManager(); financialCommit(r);
        LocalDateTime originalDeadline = r.getDeadlineAt();
        service().creditNow(101L);
        assertNotNull(r.getCreditedAt()); assertNull(r.getNotifiedAt());
        assertEquals(originalDeadline, r.getDeadlineAt());
        assertEquals(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY,r.getStatus());
        service().creditNow(101L);
        verify(manualPayments,times(1)).confirmRecipientReviewPayment(eq(101L),eq(71L),eq(140000L),anyString(),eq(""),eq("manager"),any(),any(),eq(type.name()),eq(r.getRecipientProfileId()),eq(r.getRecipientKey()));
    }

    @Test void finalisationAfter24HoursOnlyChangesMetadataEvenIfNoticeWasUnavailable() {
        var r=report(); r.setCreditedAt(LocalDateTime.now().minusDays(1)); r.setDeadlineAt(LocalDateTime.now().minusSeconds(1));
        r.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION); r.setLastError("Уведомление не доставлено"); stub(r);
        service().settle(101L); service().settle(101L);
        assertEquals(SpecialistManualPaymentReviewStatus.CONFIRMED,r.getStatus());
        assertEquals("NO_OBJECTION_24H",r.getResolutionKind()); assertNotNull(r.getSettledAt());
        verifyNoInteractions(manualPayments,users);
    }

    @Test void uncreditedLegacyReportCannotBeFinalisedAsPaid() {
        var r=report(); r.setDeadlineAt(LocalDateTime.now().minusDays(1)); stub(r);
        service().settle(101L);
        assertNull(r.getCreditedAt()); assertNull(r.getSettledAt()); verifyNoInteractions(manualPayments);
    }

    @Test void disputeBeforeDeadlinePermanentlyStopsAutomaticFinalisationWithoutReversingReceipt() {
        var r=report(); var credited=LocalDateTime.now().minusDays(1); r.setCreditedAt(credited);
        r.setDeadlineAt(LocalDateTime.now().minusSeconds(1)); r.setDisputedAt(LocalDateTime.now().minusMinutes(5));
        r.setStatus(SpecialistManualPaymentReviewStatus.DISPUTED); stub(r);
        service().settle(101L); service().creditNow(101L);
        assertEquals(SpecialistManualPaymentReviewStatus.DISPUTED,r.getStatus()); assertEquals(credited,r.getCreditedAt());
        assertNull(r.getSettledAt()); verifyNoInteractions(manualPayments);
    }

    @Test void legacyDeliveryQuarantineCreditsSameReceiptWithoutResettingUnknownTelegramAttemptOrDeadline() {
        var r=report(); r.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
        r.setLastError("Исход отправки Telegram не определён; повтор заблокирован");
        var token=LocalDateTime.now().minusHours(2); r.setLastAttemptAt(token); var deadline=r.getDeadlineAt();
        stub(r);activeManager();financialCommit(r);service().creditNow(101L);
        assertNotNull(r.getCreditedAt()); assertEquals(token,r.getLastAttemptAt()); assertEquals(deadline,r.getDeadlineAt());
        assertEquals(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION,r.getStatus());
    }

    @Test void financialAmbiguityIsNotAConvertibleNotificationFailure() {
        var r=report();r.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);r.setLastError("T-Bank исход отмены неизвестен");stub(r);
        service().creditNow(101L); assertNull(r.getCreditedAt()); verifyNoInteractions(manualPayments,users);
    }

    @Test void missingFinancialCommitMarkerFailsClosed() {
        var r=report();stub(r);activeManager();
        assertThrows(IllegalStateException.class,()->service().creditNow(101L));
        assertNull(r.getCreditedAt());assertEquals(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION,r.getStatus());
    }

    @Test void bankReceiptWinsWithoutManualRecredit() {
        when(reconciliation.supersedeOnConfirmedBankPayment(101L)).thenReturn(true);
        service().creditNow(101L);verifyNoInteractions(manualPayments,reviews,users);
    }

    @Test void committedReceiptSurvivesAfterCommitExceptionAndIsNotRetried() {
        var r=report();stub(r);activeManager();
        when(manualPayments.confirmRecipientReviewPayment(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenAnswer(call->{r.setCreditedAt(LocalDateTime.now());throw new IllegalStateException("after commit callback");});
        assertDoesNotThrow(()->service().creditNow(101L));assertNotNull(r.getCreditedAt());
        assertEquals(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY,r.getStatus());
    }

    @Test void administratorVerifiesAlreadyCreditedDisputeWithoutPostingMoneyAgain() {
        var r=report();r.setCreditedAt(LocalDateTime.now().minusHours(1));r.setStatus(SpecialistManualPaymentReviewStatus.DISPUTED);stub(r);
        var admin=new UsernamePasswordAuthenticationToken("admin",null);
        assertThrows(ResponseStatusException.class,()->service().verifyReceived(101L,true,true,139999L,"Выписка сверена",admin));
        service().verifyReceived(101L,true,true,140000L,"Выписка сверена",admin);
        assertEquals(SpecialistManualPaymentReviewStatus.CONFIRMED,r.getStatus());assertEquals("ADMIN_VERIFIED_RECEIPT",r.getResolutionKind());
        verifyNoInteractions(manualPayments);
    }

    private void financialCommit(SpecialistManualPaymentReview r) {
        when(manualPayments.confirmRecipientReviewPayment(any(),any(),any(),any(),any(),any(),any(),any(),any(),nullable(Long.class),any()))
                .thenAnswer(call->{r.setCreditedAt(LocalDateTime.now());return null;});
    }
    private void stub(SpecialistManualPaymentReview r){when(reviews.lockById(101L)).thenReturn(Optional.of(r));}
    private void activeManager(){when(users.activeActor(17L)).thenReturn(Optional.of(new SpecialistPaymentReviewDirectory.Actor("manager",List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))));}
    private SpecialistManualPaymentReviewSettlementService service(){return new SpecialistManualPaymentReviewSettlementService(reviews,new PaymentLinkTransactionExecutor(),manualPayments,users,issues,targetAccess,reconciliation);}
    private SpecialistManualPaymentReview report(){
        var r=new SpecialistManualPaymentReview();r.setId(101L);r.setOrderId(7L);r.setPaymentLinkId(71L);r.setAmountKopecks(140000L);
        r.setRecipientProfileId(27L);r.setRecipientUserId(37L);r.setRecipientKey("profile:27");r.setRequestedByUserId(17L);r.setRequestedBy("manager");
        r.setReason("Проверенный чек");r.setReceiptUrl("");r.setRequestedAt(LocalDateTime.now());r.setDeadlineAt(r.getRequestedAt().plusHours(24));
        r.setStatus(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY);return r;
    }
}
