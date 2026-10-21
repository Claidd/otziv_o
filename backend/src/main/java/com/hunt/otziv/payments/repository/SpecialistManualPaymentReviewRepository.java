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

    @Query("select r.id from SpecialistManualPaymentReview r where r.status = :status order by r.id")
    List<Long> findIdsByStatus(@Param("status") SpecialistManualPaymentReviewStatus status,
                               org.springframework.data.domain.Pageable page);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SpecialistManualPaymentReview r where r.orderId = :orderId "
            + "and r.status in :statuses order by r.id")
    List<SpecialistManualPaymentReview> lockUnresolvedByOrderId(
            @Param("orderId") Long orderId,
            @Param("statuses") Collection<SpecialistManualPaymentReviewStatus> statuses);

    Optional<SpecialistManualPaymentReview> findByPaymentLinkId(Long linkId);

    List<SpecialistManualPaymentReview> findByStatusInOrderByRequestedAtDesc(
            Collection<SpecialistManualPaymentReviewStatus> statuses,
            org.springframework.data.domain.Pageable page);
}
