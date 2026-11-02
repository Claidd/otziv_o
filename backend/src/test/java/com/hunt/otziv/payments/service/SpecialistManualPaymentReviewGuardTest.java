package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecialistManualPaymentReviewGuardTest {
    @Mock SpecialistManualPaymentReviewRepository reviews;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "credited", "disputed", "settling", "different-link", "different-amount", "other-failure",
            "no-operation", "no-url", "cancel-pending", "different-method", "another-review", "unissued"})
    void recoveryNeverAllowsANewOrCompetingBankPayment(String change) {
        PaymentLink link = link();
        link.setStatus(com.hunt.otziv.payments.model.PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.SBP_QR);
        var profile = new com.hunt.otziv.payments.model.PaymentProfile();
        profile.setProvider(com.hunt.otziv.payments.model.PaymentProfile.PROVIDER_TOCHKA);
        link.setPaymentProfile(profile); link.setTbankPaymentId("original-operation");
        link.setPaymentUrl("https://merch.securepaytb.ru/pay/original-operation");
        var review = review(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
        review.setLastError(ManualCardRoutePolicy.ACTIVE_TOCHKA_MANUAL_ERROR_PREFIX + " нельзя закрыть");
        if (change.equals("credited")) review.setCreditedAt(java.time.LocalDateTime.now());
        if (change.equals("disputed")) review.setDisputedAt(java.time.LocalDateTime.now());
        if (change.equals("settling")) review.setStatus(SpecialistManualPaymentReviewStatus.SETTLING);
        if (change.equals("different-link")) review.setPaymentLinkId(72L);
        if (change.equals("different-amount")) review.setAmountKopecks(150_000L);
        if (change.equals("other-failure")) review.setLastError("Неизвестный исход ручного зачисления");
        if (change.equals("no-operation")) link.setTbankPaymentId(null);
        if (change.equals("no-url")) link.setPaymentUrl(null);
        if (change.equals("cancel-pending")) link.setBankCancelNonce("cancel-claim");
        if (change.equals("different-method")) link.setPaymentMethod(com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM);
        if (change.equals("unissued")) link.setStatus(com.hunt.otziv.payments.model.PaymentLinkStatus.CREATED);
        when(reviews.lockUnresolvedByOrderId(eq(7L), anyCollection())).thenReturn(
                change.equals("another-review") ? List.of(review, review(SpecialistManualPaymentReviewStatus.WAITING)) : List.of(review));
        assertThrows(ResponseStatusException.class, () -> new SpecialistManualPaymentReviewGuard(reviews)
                .requireBankPaymentAccess(link, com.hunt.otziv.payments.model.PaymentMethod.SBP_QR));
    }

    @Test
    void unresolvedReportBlocksNewInstructionsAndGenericPaidTransition() {
        when(reviews.existsByOrderIdAndStatusIn(eq(7L), anyCollection())).thenReturn(true);
        SpecialistManualPaymentReviewGuard guard = new SpecialistManualPaymentReviewGuard(reviews);

        assertThrows(ResponseStatusException.class, () -> guard.requireNoPendingReview(7L));
        assertThrows(ResponseStatusException.class, () -> guard.requireNoPendingReviewForManualPaid(7L));
    }

    @Test
    void settlingStatusesBlockEveryGenericPaidTransition() {
        when(reviews.existsByOrderIdAndStatusIn(eq(7L), anyCollection()))
                .thenAnswer(call -> {
                    Collection<SpecialistManualPaymentReviewStatus> statuses = call.getArgument(1);
                    return statuses.contains(SpecialistManualPaymentReviewStatus.SETTLING)
                            && statuses.contains(SpecialistManualPaymentReviewStatus.ADMIN_SETTLING);
                });
        SpecialistManualPaymentReviewGuard guard = new SpecialistManualPaymentReviewGuard(reviews);

        assertThrows(ResponseStatusException.class, () -> guard.requireNoPendingReview(7L));
        assertThrows(ResponseStatusException.class, () -> guard.requireNoPendingReviewForManualPaid(7L));
    }

    @Test
    void onlyMatchingSpecialistReviewMaySettleUnderOrderAndLinkLocks() {
        SpecialistManualPaymentReviewGuard guard = new SpecialistManualPaymentReviewGuard(reviews);
        PaymentLink link = link();
        for (SpecialistManualPaymentReviewStatus status : List.of(
                SpecialistManualPaymentReviewStatus.SETTLING,
                SpecialistManualPaymentReviewStatus.ADMIN_SETTLING)) {
            SpecialistManualPaymentReview review = review(status);
            when(reviews.lockUnresolvedByOrderId(eq(7L), anyCollection()))
                    .thenReturn(List.of(review));

            assertDoesNotThrow(() -> guard.requireManualPaymentAllowed(7L, link, 140_000L,
                    true, 27L, "profile:27", true, 101L, status));
            assertThrows(ResponseStatusException.class, () -> guard.requireManualPaymentAllowed(
                    7L, link, 140_000L, true,
                    28L, "profile:27", true, 101L, status));
            assertThrows(ResponseStatusException.class, () -> guard.requireManualPaymentAllowed(
                    7L, link, 140_000L, true,
                    27L, "profile:27", true, null, null));
        }
    }

    @Test
    void newlyOpenedReviewBlocksTheFinalPaymentCheck() {
        SpecialistManualPaymentReviewGuard guard = new SpecialistManualPaymentReviewGuard(reviews);
        when(reviews.lockUnresolvedByOrderId(eq(7L), anyCollection()))
                .thenReturn(List.of())
                .thenReturn(List.of(review(SpecialistManualPaymentReviewStatus.WAITING)));

        assertDoesNotThrow(() -> guard.requireManualPaymentAllowed(7L, link(), 140_000L,
                true, 27L, "profile:27", true, null, null));
        assertThrows(ResponseStatusException.class, () -> guard.requireManualPaymentAllowed(
                7L, link(), 140_000L, true,
                27L, "profile:27", true, null, null));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ContractorRecipientType.class)
    void everyRecipientRequiresExactFrozenTypeKeyAndProfile(ContractorRecipientType type) {
        var guard=new SpecialistManualPaymentReviewGuard(reviews);
        var r=review(SpecialistManualPaymentReviewStatus.SETTLING);r.setRecipientType(type.name());
        var link=link();link.setManualActualRecipientType(type);
        if(type==ContractorRecipientType.OWNER){r.setRecipientProfileId(null);r.setRecipientUserId(null);r.setRecipientKey("OWNER");link.setManualActualRecipientProfileId(null);link.setManualActualRecipientUserId(null);}
        when(reviews.lockUnresolvedByOrderId(eq(7L),anyCollection())).thenReturn(List.of(r));
        assertDoesNotThrow(()->guard.requireRecipientReviewAllowed(7L,link,140000L,type.name(),r.getRecipientProfileId(),r.getRecipientKey(),type.name(),101L,r.getStatus()));
        assertThrows(ResponseStatusException.class,()->guard.requireRecipientReviewAllowed(7L,link,140000L,type.name(),r.getRecipientProfileId(),"different",type.name(),101L,r.getStatus()));
        assertThrows(ResponseStatusException.class,()->guard.requireRecipientReviewAllowed(7L,link,140001L,type.name(),r.getRecipientProfileId(),r.getRecipientKey(),type.name(),101L,r.getStatus()));
    }

    @Test void creditMarkerCannotAttachToAnotherSourceOrDisputedClaim() {
        var guard=new SpecialistManualPaymentReviewGuard(reviews);var r=review(SpecialistManualPaymentReviewStatus.SETTLING);
        when(reviews.lockById(101L)).thenReturn(java.util.Optional.of(r));
        var now=java.time.LocalDateTime.now();
        assertThrows(ResponseStatusException.class,()->guard.recordCredit(101L,72L,now));
        r.setStatus(SpecialistManualPaymentReviewStatus.DISPUTED);
        assertThrows(ResponseStatusException.class,()->guard.recordCredit(101L,71L,now));
        org.junit.jupiter.api.Assertions.assertNull(r.getCreditedAt());
        r.setStatus(SpecialistManualPaymentReviewStatus.SETTLING);guard.recordCredit(101L,71L,now);
        guard.recordCredit(101L,71L,now.plusHours(1));
        org.junit.jupiter.api.Assertions.assertEquals(now,r.getCreditedAt());
        org.mockito.Mockito.verify(reviews).saveAndFlush(r);
    }

    private PaymentLink link() {
        Order order = new Order(); order.setId(7L);
        PaymentLink link = new PaymentLink(); link.setId(71L); link.setOrder(order);
        link.setAmountKopecks(140_000L);
        link.setManualActualRecipientType(ContractorRecipientType.SPECIALIST);
        link.setManualActualRecipientProfileId(27L);
        link.setManualActualRecipientUserId(37L);
        return link;
    }

    private SpecialistManualPaymentReview review(SpecialistManualPaymentReviewStatus status) {
        SpecialistManualPaymentReview review = new SpecialistManualPaymentReview();
        review.setId(101L); review.setOrderId(7L); review.setPaymentLinkId(71L);
        review.setAmountKopecks(140_000L); review.setRecipientUserId(37L);
        review.setRecipientProfileId(27L); review.setRecipientKey("profile:27");
        review.setStatus(status);
        return review;
    }
}
