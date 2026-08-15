package com.hunt.otziv.contractor_payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ContractorPaymentAllocationRepository extends JpaRepository<ContractorPaymentAllocation, Long> {

    boolean existsByMode(ContractorAllocationMode mode);

    @Query(value = "SELECT CURRENT_TIMESTAMP(6)", nativeQuery = true)
    LocalDateTime currentDatabaseTime();

    long countByReconcileLeaseUntilAfter(LocalDateTime now);

    long countByReconcileAttemptsGreaterThan(int attempts);

    Optional<ContractorPaymentAllocation>
    findFirstByReconcileAttemptsGreaterThanOrderByUpdatedAtDesc(int attempts);

    @Query("""
        SELECT COUNT(allocation)
        FROM ContractorPaymentAllocation allocation
        WHERE allocation.reconcileClaimToken IS NOT NULL
          AND (allocation.reconcileLeaseUntil IS NULL OR allocation.reconcileLeaseUntil <= :now)
    """)
    long countExpiredReconcileClaims(@Param("now") LocalDateTime now);

    @Query("""
        SELECT COUNT(allocation)
        FROM ContractorPaymentAllocation allocation
        WHERE allocation.reconcileAttempts > 0
          AND allocation.reconcileNextRetryAt IS NOT NULL
          AND allocation.reconcileNextRetryAt <= :now
    """)
    long countDueReconcileRetries(@Param("now") LocalDateTime now);

    @Query("""
        SELECT MIN(allocation.reconcileNextRetryAt)
        FROM ContractorPaymentAllocation allocation
        WHERE allocation.reconcileAttempts > 0
    """)
    LocalDateTime findOldestReconcileRetryAt();

    @Query("""
        SELECT MIN(allocation.reconcileNextRetryAt)
        FROM ContractorPaymentAllocation allocation
        WHERE allocation.reconcileAttempts > 0
          AND allocation.reconcileNextRetryAt IS NOT NULL
          AND allocation.reconcileNextRetryAt <= :now
    """)
    LocalDateTime findOldestDueReconcileRetryAt(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT allocation FROM ContractorPaymentAllocation allocation WHERE allocation.id = :id")
    Optional<ContractorPaymentAllocation> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT allocation FROM ContractorPaymentAllocation allocation WHERE allocation.id IN :ids ORDER BY allocation.id")
    List<ContractorPaymentAllocation> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    @Query(value = """
        SELECT allocation.id
        FROM contractor_payment_allocations allocation
        WHERE allocation.mode = :mode
          AND allocation.source_type = :sourceType
          AND allocation.source_id = :sourceId
        ORDER BY allocation.attempt_no DESC, allocation.id DESC
        LIMIT 1
    """, nativeQuery = true)
    Optional<Long> findLatestId(
            @Param("mode") String mode,
            @Param("sourceType") String sourceType,
            @Param("sourceId") Long sourceId
    );

    /** Latest attempt in every accounting mode, used before canonical profile/allocation locks. */
    @Query(value = """
        SELECT allocation.id
        FROM contractor_payment_allocations allocation
        WHERE allocation.source_type = :sourceType
          AND allocation.source_id = :sourceId
          AND allocation.attempt_no = (
              SELECT MAX(latest.attempt_no)
              FROM contractor_payment_allocations latest
              WHERE latest.mode = allocation.mode
                AND latest.source_type = allocation.source_type
                AND latest.source_id = allocation.source_id
          )
        ORDER BY allocation.id
    """, nativeQuery = true)
    List<Long> findLatestIdsBySourceAcrossModes(
            @Param("sourceType") String sourceType,
            @Param("sourceId") Long sourceId
    );

    /** Non-locking prelude used only to establish profile -> allocation lock order. */
    @Query("SELECT allocation.recipientProfile.id FROM ContractorPaymentAllocation allocation WHERE allocation.id = :id")
    Optional<Long> findRecipientProfileIdById(@Param("id") Long id);

    /** Durable target used by allocation-id administrative authorization. */
    @Query("""
        SELECT allocation.recipientProfile.user.id
        FROM ContractorPaymentAllocation allocation
        WHERE allocation.id = :id
    """)
    Optional<Long> findRecipientProfileUserIdById(@Param("id") Long id);

    /** Durable contractor recipient reached through the immutable PaymentLink relation. */
    @Query("""
        SELECT allocation.recipientProfile.user.id
        FROM PaymentLink link, ContractorPaymentAllocation allocation
        WHERE link.id = :paymentLinkId
          AND allocation.id = link.contractorAllocationId
    """)
    Optional<Long> findRecipientProfileUserIdByPaymentLinkId(
            @Param("paymentLinkId") Long paymentLinkId
    );

    /** Durable contractor recipient reached through the frozen CommonInvoice relation. */
    @Query("""
        SELECT allocation.recipientProfile.user.id
        FROM CommonInvoice invoice, ContractorPaymentAllocation allocation
        WHERE invoice.id = :commonInvoiceId
          AND allocation.id = invoice.contractorAllocationId
    """)
    Optional<Long> findRecipientProfileUserIdByCommonInvoiceId(
            @Param("commonInvoiceId") Long commonInvoiceId
    );

    /**
     * Capacity is a financial decision, not a reporting snapshot. Callers
     * already hold the recipient profile mutex; this current locking read
     * makes allocations committed while that mutex was being awaited visible
     * even under MySQL REPEATABLE READ.
     */
    @Query(value = """
        SELECT
            COALESCE(SUM(GREATEST(0, allocation.confirmed_kopecks)), 0) AS confirmedKopecks,
            COALESCE(SUM(GREATEST(0, allocation.returned_kopecks)), 0) AS returnedKopecks,
            COALESCE(SUM(
                CASE
                    WHEN allocation.status IN ('RESERVED', 'CLIENT_REPORTED', 'PARTIALLY_CONFIRMED')
                        THEN GREATEST(
                            0,
                            allocation.amount_kopecks
                                - GREATEST(0, allocation.confirmed_kopecks - allocation.returned_kopecks)
                        )
                    ELSE 0
                END
            ), 0) AS outstandingKopecks
        FROM contractor_payment_allocations allocation
        WHERE allocation.recipient_profile_id = :profileId
          AND allocation.mode = :mode
        FOR UPDATE
    """, nativeQuery = true)
    CapacityTotals capacityTotalsForUpdate(
            @Param("profileId") Long profileId,
            @Param("mode") String mode
    );

    /**
     * Counts every invoice-routing attempt made during the business day,
     * including attempts later released, canceled, expired or returned. This
     * deliberately prevents repeated cancel/retry cycles from bypassing the
     * operational daily cap. Direct settlements are not client invoice routes
     * and therefore do not consume this limit.
     *
     * Callers already hold the recipient profile PESSIMISTIC_WRITE mutex. The
     * locking native read is still required so a transaction that waited for
     * that mutex observes routes committed by the previous holder under MySQL
     * REPEATABLE READ.
     */
    @Query(value = """
        SELECT
            COALESCE(SUM(GREATEST(0, allocation.amount_kopecks)), 0) AS amountKopecks,
            COUNT(*) AS routeCount
        FROM contractor_payment_allocations allocation
        WHERE allocation.recipient_profile_id = :profileId
          AND allocation.mode = :mode
          AND allocation.source_type IN ('PAYMENT_LINK', 'COMMON_INVOICE')
          AND allocation.reserved_at >= :from
          AND allocation.reserved_at < :to
        FOR UPDATE
    """, nativeQuery = true)
    DailyRoutingTotals dailyRoutingTotalsForUpdate(
            @Param("profileId") Long profileId,
            @Param("mode") String mode,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * Current attempt-generation check. Returning only the scalar id bypasses
     * Hibernate's first-level entity cache; callers have already locked the
     * evidence source and the requested attempt's profile/allocation.
     */
    @Query(value = """
        SELECT allocation.id
        FROM contractor_payment_allocations allocation
        WHERE allocation.mode = :mode
          AND allocation.source_type = :sourceType
          AND allocation.source_id = :sourceId
        ORDER BY allocation.attempt_no DESC, allocation.id DESC
        LIMIT 1
        FOR UPDATE
    """, nativeQuery = true)
    Optional<Long> findLatestIdForUpdate(
            @Param("mode") String mode,
            @Param("sourceType") String sourceType,
            @Param("sourceId") Long sourceId
    );

    interface CapacityTotals {
        Long getConfirmedKopecks();

        Long getReturnedKopecks();

        Long getOutstandingKopecks();

        default long safeConfirmedKopecks() {
            return getConfirmedKopecks() == null ? 0L : getConfirmedKopecks();
        }

        default long safeReturnedKopecks() {
            return getReturnedKopecks() == null ? 0L : getReturnedKopecks();
        }

        default long safeOutstandingKopecks() {
            return getOutstandingKopecks() == null ? 0L : getOutstandingKopecks();
        }
    }

    interface DailyRoutingTotals {
        Long getAmountKopecks();

        Long getRouteCount();
    }

    Optional<ContractorPaymentAllocation> findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
            ContractorAllocationMode mode,
            ContractorAllocationSourceType sourceType,
            Long sourceId
    );

    @Query("""
        SELECT COALESCE(SUM(a.amountKopecks), 0)
        FROM ContractorPaymentAllocation a
        WHERE a.recipientProfile.id = :profileId
          AND a.mode = :mode
          AND a.status IN :statuses
    """)
    long sumByProfileAndModeAndStatusIn(@Param("profileId") Long profileId,
                                        @Param("mode") ContractorAllocationMode mode,
                                        @Param("statuses") Collection<ContractorAllocationStatus> statuses);

    @Query("""
        SELECT COALESCE(SUM(a.amountKopecks), 0)
        FROM ContractorPaymentAllocation a
        WHERE a.recipientProfile.id = :profileId
          AND a.mode = :mode
          AND a.status IN :statuses
          AND a.confirmedAt >= :from
          AND a.confirmedAt < :to
    """)
    long sumByProfileAndModeAndStatusInAndConfirmedPeriod(
            @Param("profileId") Long profileId,
            @Param("mode") ContractorAllocationMode mode,
            @Param("statuses") Collection<ContractorAllocationStatus> statuses,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * Bounded, fair polling queue. Non-terminal allocations rotate through
     * lastReconciledAt; terminal allocations re-enter only when the payment
     * source changed, which still detects a later confirmation or refund.
     */
    @Query("""
        SELECT a
        FROM ContractorPaymentAllocation a
        WHERE a.mode = :mode
          AND a.sourceType = com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType.PAYMENT_LINK
          AND a.status IN :statuses
          AND (a.reconcileLeaseUntil IS NULL OR a.reconcileLeaseUntil < :now)
          AND (a.reconcileNextRetryAt IS NULL OR a.reconcileNextRetryAt <= :now)
          AND a.attemptNo = (
              SELECT MAX(latest.attemptNo)
              FROM ContractorPaymentAllocation latest
              WHERE latest.mode = a.mode
                AND latest.sourceType = a.sourceType
                AND latest.sourceId = a.sourceId
          )
          AND (
              a.status IN :pollStatuses
              OR a.lastReconciledAt IS NULL
              OR a.lastReconciledAt <= :terminalDueBefore
              OR a.lastReconciledAt > :now
              OR EXISTS (
                  SELECT link.id
                  FROM PaymentLink link
                  WHERE link.id = a.sourceId
                    AND (a.lastReconciledAt IS NULL OR link.updatedAt > a.lastReconciledAt)
              )
          )
        ORDER BY
          CASE WHEN a.lastReconciledAt IS NULL THEN 0 ELSE 1 END,
          a.lastReconciledAt,
          a.id
    """)
    List<ContractorPaymentAllocation> findPaymentLinksForReconciliation(
            @Param("mode") ContractorAllocationMode mode,
            @Param("statuses") Collection<ContractorAllocationStatus> statuses,
            @Param("pollStatuses") Collection<ContractorAllocationStatus> pollStatuses,
            @Param("now") LocalDateTime now,
            @Param("terminalDueBefore") LocalDateTime terminalDueBefore,
            Pageable pageable
    );

    @Query("""
        SELECT a
        FROM ContractorPaymentAllocation a
        WHERE a.mode = :mode
          AND a.sourceType = com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType.COMMON_INVOICE
          AND a.status IN :statuses
          AND (a.reconcileLeaseUntil IS NULL OR a.reconcileLeaseUntil < :now)
          AND (a.reconcileNextRetryAt IS NULL OR a.reconcileNextRetryAt <= :now)
          AND a.attemptNo = (
              SELECT MAX(latest.attemptNo)
              FROM ContractorPaymentAllocation latest
              WHERE latest.mode = a.mode
                AND latest.sourceType = a.sourceType
                AND latest.sourceId = a.sourceId
          )
          AND (
              a.status IN :pollStatuses
              OR a.lastReconciledAt IS NULL
              OR a.lastReconciledAt <= :terminalDueBefore
              OR a.lastReconciledAt > :now
              OR EXISTS (
                  SELECT invoice.id
                  FROM CommonInvoice invoice
                  WHERE invoice.id = a.sourceId
                    AND (a.lastReconciledAt IS NULL OR invoice.updatedAt > a.lastReconciledAt)
              )
          )
        ORDER BY
          CASE WHEN a.lastReconciledAt IS NULL THEN 0 ELSE 1 END,
          a.lastReconciledAt,
          a.id
    """)
    List<ContractorPaymentAllocation> findCommonInvoicesForReconciliation(
            @Param("mode") ContractorAllocationMode mode,
            @Param("statuses") Collection<ContractorAllocationStatus> statuses,
            @Param("pollStatuses") Collection<ContractorAllocationStatus> pollStatuses,
            @Param("now") LocalDateTime now,
            @Param("terminalDueBefore") LocalDateTime terminalDueBefore,
            Pageable pageable
    );

    @Modifying
    @Query("""
        UPDATE ContractorPaymentAllocation allocation
        SET allocation.reconcileClaimToken = :token,
            allocation.reconcileLeaseUntil = :leaseUntil
        WHERE allocation.id = :id
          AND (allocation.reconcileLeaseUntil IS NULL OR allocation.reconcileLeaseUntil < :now)
          AND (allocation.reconcileNextRetryAt IS NULL OR allocation.reconcileNextRetryAt <= :now)
    """)
    int claimForReconciliation(@Param("id") Long id,
                               @Param("token") String token,
                               @Param("now") LocalDateTime now,
                               @Param("leaseUntil") LocalDateTime leaseUntil);

    @Query("""
        SELECT a
        FROM ContractorPaymentAllocation a
        WHERE (
              a.orderId = :orderId
              OR EXISTS (
                  SELECT item.id
                  FROM CommonInvoiceOrder item
                  WHERE item.invoice.id = a.commonInvoiceId
                    AND item.order.id = :orderId
              )
          )
          AND a.mode = :mode
          AND a.status IN :statuses
        ORDER BY a.id
    """)
    List<ContractorPaymentAllocation> findActiveByOrderId(@Param("orderId") Long orderId,
                                                          @Param("mode") ContractorAllocationMode mode,
                                                          @Param("statuses") Collection<ContractorAllocationStatus> statuses);

    @Query("""
        SELECT COALESCE(SUM(a.amountKopecks), 0)
        FROM ContractorPaymentAllocation a
        WHERE a.recipientProfile.id = :profileId
          AND a.mode = :mode
          AND a.status = :status
    """)
    long sumByProfileAndModeAndStatus(@Param("profileId") Long profileId,
                                      @Param("mode") ContractorAllocationMode mode,
                                      @Param("status") ContractorAllocationStatus status);

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN a.amountKopecks > (a.confirmedKopecks - a.returnedKopecks)
                    THEN a.amountKopecks - (a.confirmedKopecks - a.returnedKopecks)
                ELSE 0
            END
        ), 0)
        FROM ContractorPaymentAllocation a
        WHERE a.recipientProfile.id = :profileId
          AND a.mode = :mode
          AND a.status IN :statuses
    """)
    long sumOutstandingExposure(@Param("profileId") Long profileId,
                                @Param("mode") ContractorAllocationMode mode,
                                @Param("statuses") Collection<ContractorAllocationStatus> statuses);

    @Query("""
        SELECT a
        FROM ContractorPaymentAllocation a
        WHERE (
              :userId IS NULL
              OR a.recipientUserId = :userId
              OR EXISTS (
                  SELECT w.id FROM Worker w
                  WHERE w.id = a.currentWorkerId AND w.user.id = :userId
              )
              OR EXISTS (
                  SELECT m.id FROM Manager m
                  WHERE m.id = a.currentManagerId AND m.user.id = :userId
              )
          )
          AND (:status IS NULL OR a.status = :status)
          AND (:mode IS NULL OR a.mode = :mode)
          AND (:sourceType IS NULL OR a.sourceType = :sourceType)
          AND (:sourceId IS NULL OR a.sourceId = :sourceId)
          AND (
              :excludePrivilegedTargets = false
              OR (
                  NOT EXISTS (
                      SELECT recipientRole.id
                      FROM User recipientUser
                      JOIN recipientUser.roles recipientRole
                      WHERE recipientUser.id = a.recipientUserId
                        AND recipientRole.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
                  )
                  AND NOT EXISTS (
                      SELECT workerRole.id
                      FROM Worker currentWorker
                      JOIN currentWorker.user.roles workerRole
                      WHERE currentWorker.id = a.currentWorkerId
                        AND workerRole.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
                  )
                  AND NOT EXISTS (
                      SELECT managerRole.id
                      FROM Manager currentManager
                      JOIN currentManager.user.roles managerRole
                      WHERE currentManager.id = a.currentManagerId
                        AND managerRole.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
                  )
              )
          )
        ORDER BY a.createdAt DESC, a.id DESC
    """)
    Page<ContractorPaymentAllocation> findJournal(
            @Param("userId") Long userId,
            @Param("status") ContractorAllocationStatus status,
            @Param("mode") ContractorAllocationMode mode,
            @Param("sourceType") ContractorAllocationSourceType sourceType,
            @Param("sourceId") Long sourceId,
            @Param("excludePrivilegedTargets") boolean excludePrivilegedTargets,
            Pageable pageable
    );
}
