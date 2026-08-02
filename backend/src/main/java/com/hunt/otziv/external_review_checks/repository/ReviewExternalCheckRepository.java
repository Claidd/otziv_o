package com.hunt.otziv.external_review_checks.repository;

import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckStatus;
import com.hunt.otziv.external_review_checks.model.ReviewExternalCheck;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewExternalCheckRepository extends CrudRepository<ReviewExternalCheck, Long> {

    /**
     * Uses the primary database as the lease clock so application nodes with
     * different JVM clocks or time zones cannot prematurely reclaim work.
     */
    @Query(value = "SELECT CURRENT_TIMESTAMP(6)", nativeQuery = true)
    LocalDateTime currentDatabaseTime();

    @Query("""
        SELECT CASE WHEN COUNT(c) > 0 THEN TRUE ELSE FALSE END
        FROM ReviewExternalCheck c
        WHERE c.deduplicationKeyHash = :deduplicationKeyHash
    """)
    boolean existsByDeduplicationKeyHash(
            @Param("deduplicationKeyHash") byte[] deduplicationKeyHash
    );

    @Query("""
        SELECT DISTINCT c
        FROM ReviewExternalCheck c
        JOIN FETCH c.review r
        LEFT JOIN FETCH r.orderDetails d
        LEFT JOIN FETCH d.order o
        LEFT JOIN FETCH o.filial
        WHERE c.id = :checkId
    """)
    Optional<ReviewExternalCheck> findByIdForProcessing(@Param("checkId") Long checkId);

    @Query(value = """
        SELECT r.review_id
        FROM reviews r
        LEFT JOIN order_details od ON od.order_detail_id = r.review_order_details
        LEFT JOIN orders o ON o.order_id = od.order_detail_order
        LEFT JOIN filial order_f ON order_f.filial_id = o.order_filial
        LEFT JOIN filial review_f ON review_f.filial_id = r.review_filial
        WHERE r.review_publish = 1
          AND r.review_published_marked_at IS NOT NULL
          AND r.review_published_marked_at <= :threshold
          AND (
                r.review_external_confirm_status IS NULL
                OR r.review_external_confirm_status <> 'CONFIRMED'
          )
          AND r.review_text IS NOT NULL
          AND TRIM(r.review_text) <> ''
          AND LOWER(TRIM(r.review_text)) NOT LIKE 'текст отзыва%'
          AND LOWER(TRIM(r.review_text)) NOT LIKE 'нужно подставить%'
          AND COALESCE(
                NULLIF(TRIM(order_f.filial_url), ''),
                NULLIF(TRIM(review_f.filial_url), '')
          ) IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM review_external_checks c
              WHERE c.review_id = r.review_id
          )
        ORDER BY r.review_published_marked_at ASC, r.review_id ASC
        """, nativeQuery = true)
    List<Long> findCandidateReviewIds(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    @Query("""
        SELECT MAX(c.id)
        FROM ReviewExternalCheck c
        WHERE c.review.id = :reviewId
    """)
    Optional<Long> findLatestIdByReviewId(@Param("reviewId") Long reviewId);

    @Query("""
        SELECT c.id
        FROM ReviewExternalCheck c
        WHERE c.status IN :dueStatuses
          AND c.checkAfter <= :now
          AND c.attemptCount < :maxAttempts
          AND (
                c.processingLeaseUntil IS NULL
                OR c.processingLeaseUntil <= :now
          )
        ORDER BY c.checkAfter ASC, c.id ASC
    """)
    List<Long> findDueClaimableIds(
            @Param("dueStatuses") Collection<ExternalReviewCheckStatus> dueStatuses,
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable
    );

    /**
     * Uses idx_review_external_checks_stale_claim. This is deliberately only a
     * candidate scan; every returned id must still win {@link #tryClaim}.
     */
    @Query("""
        SELECT c.id
        FROM ReviewExternalCheck c
        WHERE c.status = :checkingStatus
          AND c.attemptCount < :maxAttempts
          AND (
                c.processingLeaseUntil IS NULL
                OR c.processingLeaseUntil <= :now
          )
        ORDER BY c.processingLeaseUntil ASC, c.attemptCount ASC, c.id ASC
    """)
    List<Long> findStaleCheckingIds(
            @Param("checkingStatus") ExternalReviewCheckStatus checkingStatus,
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable
    );

    @Query("""
        SELECT c.id
        FROM ReviewExternalCheck c
        WHERE c.status = :checkingStatus
          AND c.attemptCount >= :maxAttempts
          AND (
                c.processingLeaseUntil IS NULL
                OR c.processingLeaseUntil <= :now
          )
        ORDER BY c.processingLeaseUntil ASC, c.attemptCount ASC, c.id ASC
    """)
    List<Long> findExhaustedStaleCheckingIds(
            @Param("checkingStatus") ExternalReviewCheckStatus checkingStatus,
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable
    );

    /**
     * Atomic compare-and-set claim. A candidate read is only advisory; the
     * status and attempt counter observed by the claimant are repeated here so
     * exactly one node can install a fresh token and lease.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE review_external_checks
        SET status = 'CHECKING',
            attempt_count = attempt_count + 1,
            error_message = NULL,
            processing_token = :processingToken,
            processing_owner = :processingOwner,
            processing_started_at = :now,
            processing_lease_until = :leaseUntil,
            updated_at = :now
        WHERE review_external_check_id = :checkId
          AND status = :expectedStatus
          AND attempt_count = :expectedAttemptCount
          AND attempt_count < :maxAttempts
          AND (
                (
                    :expectedStatus IN ('PENDING', 'NOT_FOUND', 'ERROR')
                    AND check_after <= :now
                    AND (
                        processing_lease_until IS NULL
                        OR processing_lease_until <= :now
                    )
                )
                OR (
                    :expectedStatus = 'CHECKING'
                    AND (
                        processing_lease_until IS NULL
                        OR processing_lease_until <= :now
                    )
                )
          )
        """, nativeQuery = true)
    int tryClaim(
            @Param("checkId") Long checkId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedAttemptCount") int expectedAttemptCount,
            @Param("maxAttempts") int maxAttempts,
            @Param("processingToken") String processingToken,
            @Param("processingOwner") String processingOwner,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT c
        FROM ReviewExternalCheck c
        WHERE c.id = :checkId
          AND c.processingToken = :processingToken
    """)
    Optional<ReviewExternalCheck> findClaimedForUpdate(
            @Param("checkId") Long checkId,
            @Param("processingToken") String processingToken
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ReviewExternalCheck c WHERE c.id = :checkId")
    Optional<ReviewExternalCheck> findByIdForUpdate(@Param("checkId") Long checkId);
}
