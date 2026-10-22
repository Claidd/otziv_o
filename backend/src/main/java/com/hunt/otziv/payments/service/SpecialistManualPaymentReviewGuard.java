package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
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
                    && specialistRecipient
                    && Objects.equals(review.getRecipientProfileId(), recipientProfileId)
                    && Objects.equals(review.getRecipientKey(), recipientKey)
                    && linkActualSpecialist
                    && Objects.equals(link.getManualActualRecipientProfileId(), review.getRecipientProfileId())
                    && Objects.equals(link.getManualActualRecipientUserId(), review.getRecipientUserId())) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Оплата специалисту ожидает сверки. Используйте исходный запрос подтверждения");
    }
}
