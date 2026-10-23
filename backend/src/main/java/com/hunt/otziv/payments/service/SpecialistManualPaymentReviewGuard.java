package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import java.time.LocalDateTime;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Prevents a second payment route or generic paid transition during specialist review. */
@Service
@RequiredArgsConstructor
public class SpecialistManualPaymentReviewGuard implements com.hunt.otziv.payments.api.SpecialistPaymentReviewGate {

    private static final EnumSet<SpecialistManualPaymentReviewStatus> UNRESOLVED = EnumSet.of(
            SpecialistManualPaymentReviewStatus.PENDING_DELIVERY,
            SpecialistManualPaymentReviewStatus.WAITING,
            SpecialistManualPaymentReviewStatus.SETTLING,
            SpecialistManualPaymentReviewStatus.ADMIN_SETTLING,
            SpecialistManualPaymentReviewStatus.DISPUTED,
            SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
    private final SpecialistManualPaymentReviewRepository reviews;

    public void requireNoPendingReview(Long orderId) {
        if (orderId != null && reviews.existsByOrderIdAndStatusIn(orderId, UNRESOLVED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Сверка перевода специалисту не завершена. Оплату и новый счёт пока нельзя подтвердить");
        }
    }

    public void requireNoPendingReviewForManualPaid(Long orderId) {
        if (orderId != null && reviews.existsByOrderIdAndStatusIn(orderId, UNRESOLVED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Оплата специалисту ожидает сверки. Заказ пока нельзя отметить оплаченным");
        }
    }

    /** Call after locking Order and PaymentLink, in the transaction that will post money. */
    public void requireManualPaymentAllowed(Long orderId, PaymentLink link, Long amountKopecks,
                                            boolean specialistRecipient,
                                            Long recipientProfileId, String recipientKey,
                                            boolean linkActualSpecialist,
                                            Long trustedReviewId,
                                            SpecialistManualPaymentReviewStatus trustedStatus) {
        requireRecipientReviewAllowed(orderId, link, amountKopecks,
                specialistRecipient ? "SPECIALIST" : null,
                recipientProfileId, recipientKey, linkActualSpecialist ? "SPECIALIST" : null, trustedReviewId, trustedStatus);
    }

    public void requireRecipientReviewAllowed(Long orderId, PaymentLink link, Long amountKopecks,
            String recipientType, Long recipientProfileId, String recipientKey,
            String actualRecipientType, Long trustedReviewId, SpecialistManualPaymentReviewStatus trustedStatus) {
        List<SpecialistManualPaymentReview> unresolved = reviews.lockUnresolvedByOrderId(orderId, UNRESOLVED);
        if (trustedReviewId == null && unresolved.isEmpty()) {
            return;
        }
        if (trustedReviewId != null && unresolved.size() == 1 && link != null) {
            SpecialistManualPaymentReview review = unresolved.get(0);
            if (Objects.equals(review.getId(), trustedReviewId)
                    && review.getStatus() == trustedStatus
                    && (trustedStatus == SpecialistManualPaymentReviewStatus.SETTLING
                        || trustedStatus == SpecialistManualPaymentReviewStatus.ADMIN_SETTLING)
                    && Objects.equals(review.getOrderId(), orderId)
                    && Objects.equals(review.getPaymentLinkId(), link.getId())
                    && Objects.equals(link.getOrderId(), orderId)
                    && Objects.equals(review.getAmountKopecks(), amountKopecks)
                    && link.getAmountKopecks() == review.getAmountKopecks()
                    && Objects.equals(recipientType, review.getRecipientType())
                    && Objects.equals(review.getRecipientProfileId(), recipientProfileId)
                    && Objects.equals(review.getRecipientKey(), recipientKey)
                    && Objects.equals(actualRecipientType, review.getRecipientType())
                    && Objects.equals(link.getManualActualRecipientProfileId(), review.getRecipientProfileId())
                    && Objects.equals(link.getManualActualRecipientUserId(), review.getRecipientUserId())) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Оплата специалисту ожидает сверки. Используйте исходный запрос подтверждения");
    }
    /** Joins the financial transaction: the credit marker cannot commit without the receipt. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordCredit(Long reviewId, Long sourceLinkId, LocalDateTime creditedAt) {
        if (reviewId == null) return;
        SpecialistManualPaymentReview review = reviews.lockById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Запрос оплаты утрачен"));
        if (!Objects.equals(review.getPaymentLinkId(), sourceLinkId)
                || (review.getStatus() != SpecialistManualPaymentReviewStatus.SETTLING
                    && review.getStatus() != SpecialistManualPaymentReviewStatus.ADMIN_SETTLING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние подтверждения оплаты изменилось");
        }
        if (review.getCreditedAt() == null) {
            review.setCreditedAt(creditedAt);
            reviews.saveAndFlush(review);
        }
    }

}
