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
