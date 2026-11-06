package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpecialistManualPaymentReviewRepository
        extends JpaRepository<SpecialistManualPaymentReview, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SpecialistManualPaymentReview r where r.paymentLinkId = :linkId")
    Optional<SpecialistManualPaymentReview> lockByPaymentLinkId(@Param("linkId") Long linkId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SpecialistManualPaymentReview r where r.id = :id")
    Optional<SpecialistManualPaymentReview> lockById(@Param("id") Long id);

    @Query("select r.id from SpecialistManualPaymentReview r where r.status = :status "
            + "and (r.lastAttemptAt is null or r.lastAttemptAt <= :cutoff) order by r.id")
    List<Long> findRetryableIds(@Param("status") SpecialistManualPaymentReviewStatus status,
                                @Param("cutoff") LocalDateTime cutoff,
                                org.springframework.data.domain.Pageable page);

    @Query("select r.id from SpecialistManualPaymentReview r where r.status = :status "
            + "and r.deadlineAt <= :now and (r.lastAttemptAt is null or r.lastAttemptAt <= :cutoff) "
            + "order by r.deadlineAt, r.id")
    List<Long> findDueIds(@Param("status") SpecialistManualPaymentReviewStatus status,
                          @Param("now") LocalDateTime now,
                          @Param("cutoff") LocalDateTime cutoff,
                          org.springframework.data.domain.Pageable page);

    boolean existsByOrderIdAndStatusIn(Long orderId, Collection<SpecialistManualPaymentReviewStatus> statuses);

    @Query("select r.id from SpecialistManualPaymentReview r, PaymentLink l left join l.order.status orderStatus "
            + "where l.id = r.paymentLinkId and l.order.id = r.orderId and l.amountKopecks = r.amountKopecks "
            + "and r.creditedAt is null and r.resolvedAt is null and r.status in :statuses "
            + "and l.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED "
            + "and l.confirmedAmountKopecks = r.amountKopecks "
            + "and l.paymentMethod in (com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM, com.hunt.otziv.payments.model.PaymentMethod.SBP_QR) "
            + "and l.paidAt is not null and l.manualConfirmedAt is null and length(trim(l.tbankPaymentId)) > 0 "
            + "and ((l.order.complete = true and l.order.payDay is not null) or orderStatus.title = 'Оплачено') order by r.id")
    List<Long> findUncreditedBankConfirmedIds(@Param("statuses") Collection<SpecialistManualPaymentReviewStatus> statuses,
            org.springframework.data.domain.Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SpecialistManualPaymentReview r where r.orderId = :orderId "
            + "and r.status in :statuses order by r.id")
    List<SpecialistManualPaymentReview> lockUnresolvedByOrderId(
            @Param("orderId") Long orderId,
            @Param("statuses") Collection<SpecialistManualPaymentReviewStatus> statuses);

    Optional<SpecialistManualPaymentReview> findFirstByOrderIdOrderByIdDesc(Long orderId);

    // Scalar reads do not place a stale review entity in the persistence context before its lock.
    @Query("select max(r.id) from SpecialistManualPaymentReview r where r.orderId = :orderId")
    Optional<Long> findLatestIdByOrderId(@Param("orderId") Long orderId);

    interface ReviewBinding {
        Long getOrderId();
        Long getPaymentLinkId();
        Long getRecipientUserId();
    }

    @Query("select r.orderId as orderId, r.paymentLinkId as paymentLinkId, "
            + "r.recipientUserId as recipientUserId from SpecialistManualPaymentReview r where r.id = :id")
    Optional<ReviewBinding> findBindingById(@Param("id") Long id);


    @Query("select r.id from SpecialistManualPaymentReview r where r.creditedAt is null "
            + "and r.disputedAt is null and r.resolvedAt is null and r.status in :statuses "
            + "and (r.creditNextAttemptAt is null or r.creditNextAttemptAt <= :now) "
            + "and (r.status <> com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus.SETTLING "
            + "or r.creditClaimedAt <= :cutoff) "
            + "and (r.status <> com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION "
            + "or r.lastError like 'Уведомление%' or r.lastError like 'Исход отправки Telegram%' "
            + "or r.lastError like 'Исход предыдущей отправки Telegram%' "
            + "or r.lastError like 'За 24 часа не было подтверждено уведомление%') order by r.id")
    List<Long> findUncreditedIds(@Param("statuses") Collection<SpecialistManualPaymentReviewStatus> statuses,
            @Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now,
            org.springframework.data.domain.Pageable page);

    @Query("select r.id from SpecialistManualPaymentReview r where r.creditedAt is not null "
            + "and r.status = com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus.SETTLING "
            + "and r.creditClaimedAt <= :cutoff order by r.id")
    List<Long> findCommittedClaimIds(@Param("cutoff") LocalDateTime cutoff,
            org.springframework.data.domain.Pageable page);

    @Query("select r.id from SpecialistManualPaymentReview r where r.creditedAt is not null "
            + "and r.disputedAt is null and r.deadlineAt <= :now and r.status in :statuses order by r.deadlineAt,r.id")
    List<Long> findCreditedDueIds(@Param("statuses") Collection<SpecialistManualPaymentReviewStatus> statuses,
            @Param("now") LocalDateTime now, org.springframework.data.domain.Pageable page);

    Optional<SpecialistManualPaymentReview> findByPaymentLinkId(Long linkId);

    @Query("select r.id from SpecialistManualPaymentReview r where r.lastError is not null "
            + "and r.status = com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus.CONFIRMED "
            + "and r.creditedAt is not null and r.settledAt is not null and r.resolvedAt is not null "
            + "and r.disputedAt is null order by r.id")
    List<Long> findFinalizedNoticeFailureIds(org.springframework.data.domain.Pageable page);

    List<SpecialistManualPaymentReview> findByStatusInOrderByRequestedAtDesc(
            Collection<SpecialistManualPaymentReviewStatus> statuses,
            org.springframework.data.domain.Pageable page);
}
