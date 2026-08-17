package com.hunt.otziv.payments.repository;

import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.payments.dto.PaymentLinkAdminSummary;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryItem;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.LockModeType;
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
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentLinkRepository extends JpaRepository<PaymentLink, Long> {

    Optional<PaymentLink> findByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT link FROM PaymentLink link WHERE link.token = :token")
    Optional<PaymentLink> findByTokenForUpdate(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT link FROM PaymentLink link WHERE link.id = :id")
    Optional<PaymentLink> findByIdForUpdate(@Param("id") Long id);

    List<PaymentLink> findTop100ByOrderByCreatedAtDesc();

    boolean existsByOrder_IdAndStatusIn(Long orderId, Collection<PaymentLinkStatus> statuses);

    @Query("SELECT link FROM PaymentLink link WHERE link.order.id IN :orderIds ORDER BY link.order.id, link.id")
    List<PaymentLink> findByOrderIdInForRead(@Param("orderIds") Collection<Long> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT link FROM PaymentLink link WHERE link.order.id = :orderId ORDER BY link.id")
    List<PaymentLink> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT link
        FROM PaymentLink link
        WHERE link.order.id = :orderId
          AND link.status IN :statuses
        ORDER BY link.id
    """)
    List<PaymentLink> findByOrderIdAndStatusInForUpdate(
            @Param("orderId") Long orderId,
            @Param("statuses") Collection<PaymentLinkStatus> statuses
    );

    @Query(
            value = """
                SELECT link
                FROM PaymentLink link
                LEFT JOIN FETCH link.paymentProfile
                LEFT JOIN FETCH link.manualPaymentTask task
                LEFT JOIN FETCH link.order o
                LEFT JOIN FETCH o.company c
                LEFT JOIN FETCH c.manager cm
                LEFT JOIN FETCH cm.user
                LEFT JOIN FETCH cm.paymentProfile
                LEFT JOIN FETCH o.filial f
                LEFT JOIN FETCH f.city
                LEFT JOIN FETCH o.manager m
                LEFT JOIN FETCH m.user
                LEFT JOIN FETCH m.paymentProfile
                WHERE (:from IS NULL OR link.createdAt >= :from)
                  AND (:to IS NULL OR link.createdAt < :to)
                  AND (
                    :statusFilter = 'all'
                    OR (:statusFilter = 'active' AND link.status IN :activeStatuses)
                    OR (:statusFilter = 'paid' AND link.status IN :paidStatuses)
                    OR (:statusFilter = 'refunded' AND link.status IN :refundedStatuses)
                    OR (:statusFilter = 'failed' AND link.status IN :failedStatuses)
                    OR (:statusFilter = 'created' AND link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CREATED)
                    OR (:statusFilter = 'manual' AND link.paymentMethod IN :manualMethods)
                  )
                  AND (
                    :excludePrivilegedTargets = false
                    OR NOT EXISTS (
                      SELECT recipientRole.id
                      FROM ContractorPaymentAllocation allocation
                      JOIN allocation.recipientProfile recipientProfile
                      JOIN recipientProfile.user recipientUser
                      JOIN recipientUser.roles recipientRole
                      WHERE allocation.id = link.contractorAllocationId
                        AND recipientRole.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
                    )
                  )
                  AND (
                    :searchText IS NULL
                    OR LOWER(COALESCE(c.title, '')) LIKE :searchText
                    OR LOWER(COALESCE(f.title, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.description, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.tbankPaymentId, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.tbankOrderId, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.paymentProfileName, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.tbankTerminalKey, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.payerEmail, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualPhone, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualRecipientName, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualPaymentUrl, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualPaymentButtonLabel, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualComment, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.paymentSuccessNotificationError, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.lastError, '')) LIKE :searchText
                    OR (:searchId IS NOT NULL AND (link.id = :searchId OR o.id = :searchId))
                  )
                ORDER BY link.createdAt DESC, link.id DESC
            """,
            countQuery = """
                SELECT COUNT(link)
                FROM PaymentLink link
                LEFT JOIN link.order o
                LEFT JOIN o.company c
                LEFT JOIN o.filial f
                WHERE (:from IS NULL OR link.createdAt >= :from)
                  AND (:to IS NULL OR link.createdAt < :to)
                  AND (
                    :statusFilter = 'all'
                    OR (:statusFilter = 'active' AND link.status IN :activeStatuses)
                    OR (:statusFilter = 'paid' AND link.status IN :paidStatuses)
                    OR (:statusFilter = 'refunded' AND link.status IN :refundedStatuses)
                    OR (:statusFilter = 'failed' AND link.status IN :failedStatuses)
                    OR (:statusFilter = 'created' AND link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CREATED)
                    OR (:statusFilter = 'manual' AND link.paymentMethod IN :manualMethods)
                  )
                  AND (
                    :excludePrivilegedTargets = false
                    OR NOT EXISTS (
                      SELECT recipientRole.id
                      FROM ContractorPaymentAllocation allocation
                      JOIN allocation.recipientProfile recipientProfile
                      JOIN recipientProfile.user recipientUser
                      JOIN recipientUser.roles recipientRole
                      WHERE allocation.id = link.contractorAllocationId
                        AND recipientRole.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
                    )
                  )
                  AND (
                    :searchText IS NULL
                    OR LOWER(COALESCE(c.title, '')) LIKE :searchText
                    OR LOWER(COALESCE(f.title, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.description, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.tbankPaymentId, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.tbankOrderId, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.paymentProfileName, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.tbankTerminalKey, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.payerEmail, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualPhone, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualRecipientName, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualPaymentUrl, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualPaymentButtonLabel, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.manualComment, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.paymentSuccessNotificationError, '')) LIKE :searchText
                    OR LOWER(COALESCE(link.lastError, '')) LIKE :searchText
                    OR (:searchId IS NOT NULL AND (link.id = :searchId OR o.id = :searchId))
                  )
            """
    )
    Page<PaymentLink> findAdminPage(
            @Param("statusFilter") String statusFilter,
            @Param("searchText") String searchText,
            @Param("searchId") Long searchId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeStatuses") Collection<PaymentLinkStatus> activeStatuses,
            @Param("paidStatuses") Collection<PaymentLinkStatus> paidStatuses,
            @Param("refundedStatuses") Collection<PaymentLinkStatus> refundedStatuses,
            @Param("failedStatuses") Collection<PaymentLinkStatus> failedStatuses,
            @Param("manualMethods") Collection<PaymentMethod> manualMethods,
            @Param("excludePrivilegedTargets") boolean excludePrivilegedTargets,
            Pageable pageable
    );

    @Query("""
        SELECT new com.hunt.otziv.payments.dto.PaymentLinkAdminSummary(
            COUNT(link),
            COALESCE(SUM(link.amountKopecks), 0),
            COALESCE(SUM(CASE WHEN link.status IN :paidStatuses THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.paymentMethod IN :manualMethods AND link.status IN :manualPendingStatuses THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED AND link.paymentSuccessNotifiedAt IS NOT NULL THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED AND link.paymentSuccessNotifiedAt IS NULL AND link.paymentSuccessNotificationError IS NOT NULL THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status IN :refundableStatuses AND link.tbankPaymentId IS NOT NULL AND link.tbankPaymentId <> '' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status IN :refundedStatuses THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status IN :rejectedStatuses THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE
                WHEN link.paymentMethod IN :manualMethods
                  AND link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED
                  AND link.receiptStatus = :receiptPendingStatus
                THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE
                WHEN link.paymentMethod IN :manualMethods
                  AND link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED
                  AND link.receiptStatus = :receiptPendingStatus
                  AND link.paidAt IS NOT NULL
                  AND link.paidAt <= :receiptOverdueBefore
                THEN 1 ELSE 0 END), 0)
        )
        FROM PaymentLink link
        LEFT JOIN link.order o
        LEFT JOIN o.company c
        LEFT JOIN o.filial f
        WHERE (:from IS NULL OR link.createdAt >= :from)
          AND (:to IS NULL OR link.createdAt < :to)
          AND (
            :statusFilter = 'all'
            OR (:statusFilter = 'active' AND link.status IN :activeStatuses)
            OR (:statusFilter = 'paid' AND link.status IN :paidStatuses)
            OR (:statusFilter = 'refunded' AND link.status IN :refundedStatuses)
            OR (:statusFilter = 'failed' AND link.status IN :failedStatuses)
            OR (:statusFilter = 'created' AND link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CREATED)
            OR (:statusFilter = 'manual' AND link.paymentMethod IN :manualMethods)
          )
          AND (
            :excludePrivilegedTargets = false
            OR NOT EXISTS (
              SELECT recipientRole.id
              FROM ContractorPaymentAllocation allocation
              JOIN allocation.recipientProfile recipientProfile
              JOIN recipientProfile.user recipientUser
              JOIN recipientUser.roles recipientRole
              WHERE allocation.id = link.contractorAllocationId
                AND recipientRole.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
            )
          )
          AND (
            :searchText IS NULL
            OR LOWER(COALESCE(c.title, '')) LIKE :searchText
            OR LOWER(COALESCE(f.title, '')) LIKE :searchText
            OR LOWER(COALESCE(link.description, '')) LIKE :searchText
            OR LOWER(COALESCE(link.tbankPaymentId, '')) LIKE :searchText
            OR LOWER(COALESCE(link.tbankOrderId, '')) LIKE :searchText
            OR LOWER(COALESCE(link.paymentProfileName, '')) LIKE :searchText
            OR LOWER(COALESCE(link.tbankTerminalKey, '')) LIKE :searchText
            OR LOWER(COALESCE(link.payerEmail, '')) LIKE :searchText
            OR LOWER(COALESCE(link.manualPhone, '')) LIKE :searchText
            OR LOWER(COALESCE(link.manualRecipientName, '')) LIKE :searchText
            OR LOWER(COALESCE(link.manualPaymentUrl, '')) LIKE :searchText
            OR LOWER(COALESCE(link.manualPaymentButtonLabel, '')) LIKE :searchText
            OR LOWER(COALESCE(link.manualComment, '')) LIKE :searchText
            OR LOWER(COALESCE(link.paymentSuccessNotificationError, '')) LIKE :searchText
            OR LOWER(COALESCE(link.lastError, '')) LIKE :searchText
            OR (:searchId IS NOT NULL AND (link.id = :searchId OR o.id = :searchId))
          )
    """)
    PaymentLinkAdminSummary summarizeAdminPage(
            @Param("statusFilter") String statusFilter,
            @Param("searchText") String searchText,
            @Param("searchId") Long searchId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeStatuses") Collection<PaymentLinkStatus> activeStatuses,
            @Param("paidStatuses") Collection<PaymentLinkStatus> paidStatuses,
            @Param("refundedStatuses") Collection<PaymentLinkStatus> refundedStatuses,
            @Param("failedStatuses") Collection<PaymentLinkStatus> failedStatuses,
            @Param("manualMethods") Collection<PaymentMethod> manualMethods,
            @Param("manualPendingStatuses") Collection<PaymentLinkStatus> manualPendingStatuses,
            @Param("refundableStatuses") Collection<PaymentLinkStatus> refundableStatuses,
            @Param("rejectedStatuses") Collection<PaymentLinkStatus> rejectedStatuses,
            @Param("receiptPendingStatus") PaymentReceiptStatus receiptPendingStatus,
            @Param("receiptOverdueBefore") LocalDateTime receiptOverdueBefore,
            @Param("excludePrivilegedTargets") boolean excludePrivilegedTargets
    );

    Optional<PaymentLink> findFirstByOrder_IdAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
            Long orderId,
            Collection<PaymentLinkStatus> statuses,
            LocalDateTime now
    );

    Optional<PaymentLink> findFirstByOrder_IdAndPaymentMethodInAndStatusInOrderByCreatedAtDesc(
            Long orderId,
            Collection<PaymentMethod> paymentMethods,
            Collection<PaymentLinkStatus> statuses
    );

    List<PaymentLink> findByOrder_IdAndStatusIn(Long orderId, Collection<PaymentLinkStatus> statuses);

    Optional<PaymentLink> findFirstByOrder_IdAndStatusAndLastErrorOrderByPaidAtDesc(
            Long orderId,
            PaymentLinkStatus status,
            String lastError
    );

    Optional<PaymentLink> findFirstByOrder_IdAndStatusAndLastErrorStartingWithOrderByPaidAtDesc(
            Long orderId,
            PaymentLinkStatus status,
            String lastErrorPrefix
    );

    Optional<PaymentLink> findFirstByOrder_IdAndStatusAndLastErrorIsNullOrderByPaidAtDesc(
            Long orderId,
            PaymentLinkStatus status
    );

    @Query("""
        SELECT link.id AS id,
               link.bankReconciliationAttemptedAt AS attemptedAt,
               link.updatedAt AS updatedAt
        FROM PaymentLink link
        WHERE link.status IN :statuses
          AND link.tbankPaymentId IS NOT NULL
          AND link.tbankPaymentId <> ''
          AND link.updatedAt <= :updatedBefore
          AND (
              link.bankReconciliationAttemptedAt IS NULL
              OR link.bankReconciliationAttemptedAt <= :attemptBefore
          )
        ORDER BY link.bankReconciliationAttemptedAt ASC, link.updatedAt ASC, link.id ASC
    """)
    List<PaymentBankReconciliationCandidateView> findStatusBankReconciliationCandidates(
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("attemptBefore") LocalDateTime attemptBefore,
            Pageable pageable
    );

    @Query("""
        SELECT link.id AS id,
               link.bankReconciliationAttemptedAt AS attemptedAt,
               link.updatedAt AS updatedAt
        FROM PaymentLink link
        WHERE link.bankCancelOriginStatus IS NOT NULL
          AND link.tbankPaymentId IS NOT NULL
          AND link.tbankPaymentId <> ''
          AND link.updatedAt <= :updatedBefore
          AND (
              link.bankReconciliationAttemptedAt IS NULL
              OR link.bankReconciliationAttemptedAt <= :attemptBefore
          )
        ORDER BY link.bankReconciliationAttemptedAt ASC, link.updatedAt ASC, link.id ASC
    """)
    List<PaymentBankReconciliationCandidateView> findCancelBankReconciliationCandidates(
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("attemptBefore") LocalDateTime attemptBefore,
            Pageable pageable
    );

    @Query("""
        SELECT link.id
        FROM PaymentLink link
        WHERE link.bankInitNonce IS NOT NULL
          AND (link.bankInitLeaseUntil IS NULL OR link.bankInitLeaseUntil <= :expiredBefore)
        ORDER BY link.bankInitLeaseUntil ASC, link.id ASC
    """)
    List<Long> findExpiredBankInitReservationIds(
            @Param("expiredBefore") LocalDateTime expiredBefore,
            Pageable pageable
    );

    @Query(value = """
        SELECT
            paid.order_id AS orderId,
            paid.confirmed_kopecks AS confirmedKopecks,
            COALESCE(checks.check_kopecks, 0) AS checkKopecks
        FROM (
            SELECT
                link.order_id,
                SUM(COALESCE(link.confirmed_amount_kopecks, link.amount_kopecks)) AS confirmed_kopecks
            FROM payment_links link
            JOIN (
                SELECT check_order, MAX(check_date) AS current_check_date
                FROM payment_check
                WHERE check_active = 1
                GROUP BY check_order
            ) current_check ON current_check.check_order = link.order_id
            WHERE link.status IN ('CONFIRMED', 'AMOUNT_MISMATCH')
              AND link.paid_at >= :paidSince
              AND link.paid_at >= TIMESTAMP(current_check.current_check_date)
            GROUP BY link.order_id
        ) paid
        JOIN (
            SELECT check_order, ROUND(SUM(check_sum) * 100) AS check_kopecks
            FROM payment_check
            WHERE check_active = 1
            GROUP BY check_order
        ) checks ON checks.check_order = paid.order_id
        WHERE paid.confirmed_kopecks <> checks.check_kopecks
        ORDER BY paid.order_id
    """, nativeQuery = true)
    List<PaymentAccountingMismatchView> findAccountingMismatches(@Param("paidSince") LocalDateTime paidSince);

    @Query("""
        SELECT DISTINCT link
        FROM PaymentLink link
        JOIN FETCH link.order o
        JOIN FETCH o.company c
        WHERE o.manager = :manager
          AND link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED
          AND link.paymentSuccessNotifiedAt IS NULL
          AND link.paymentSuccessNotificationError IS NOT NULL
          AND c.telegramGroupChatId IS NOT NULL
          AND (
              LOWER(link.paymentSuccessNotificationError) LIKE '%telegram%'
              OR LOWER(link.paymentSuccessNotificationError) LIKE '%supergroup%'
              OR LOWER(link.paymentSuccessNotificationError) LIKE '%migrate%'
          )
        ORDER BY link.updatedAt DESC, link.id DESC
    """)
    List<PaymentLink> findTelegramSuccessNotificationErrorsByManager(@Param("manager") Manager manager);

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN link.confirmedAmountKopecks IS NOT NULL THEN link.confirmedAmountKopecks
            WHEN link.reservedAmountKopecks IS NOT NULL THEN link.reservedAmountKopecks
            ELSE link.amountKopecks
        END), 0)
        FROM PaymentLink link
        WHERE link.paymentProfile.id = :profileId
          AND link.paymentMethod IN :paymentMethods
          AND (link.manualSource IS NULL OR link.manualSource = com.hunt.otziv.payments.model.ManualPaymentSource.PROFILE_MONTHLY_LIMIT)
          AND link.status IN :statuses
          AND link.createdAt >= :from
          AND link.createdAt < :to
          AND (:excludedLinkId IS NULL OR link.id <> :excludedLinkId)
          AND (link.status = :confirmedStatus OR link.expiresAt > :activeAt)
    """)
    long sumManualReservedAndConfirmedForPeriod(
            @Param("profileId") Long profileId,
            @Param("paymentMethods") Collection<PaymentMethod> paymentMethods,
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeAt") LocalDateTime activeAt,
            @Param("confirmedStatus") PaymentLinkStatus confirmedStatus,
            @Param("excludedLinkId") Long excludedLinkId
    );

    @Query("""
        SELECT COUNT(link)
        FROM PaymentLink link
        WHERE link.paymentProfile.id = :profileId
          AND link.paymentMethod IN :paymentMethods
          AND (link.manualSource IS NULL OR link.manualSource = com.hunt.otziv.payments.model.ManualPaymentSource.PROFILE_MONTHLY_LIMIT)
          AND link.status IN :statuses
          AND link.createdAt >= :from
          AND link.createdAt < :to
          AND link.expiresAt > :activeAt
    """)
    long countManualReservedAndConfirmedForPeriod(
            @Param("profileId") Long profileId,
            @Param("paymentMethods") Collection<PaymentMethod> paymentMethods,
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeAt") LocalDateTime activeAt
    );

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN link.confirmedAmountKopecks IS NOT NULL THEN link.confirmedAmountKopecks
            WHEN link.reservedAmountKopecks IS NOT NULL THEN link.reservedAmountKopecks
            ELSE link.amountKopecks
        END), 0)
        FROM PaymentLink link
        WHERE link.manualPaymentTask.id = :taskId
          AND link.paymentMethod IN :paymentMethods
          AND link.status IN :statuses
          AND (:excludedLinkId IS NULL OR link.id <> :excludedLinkId)
          AND (link.status = :confirmedStatus OR link.expiresAt > :activeAt)
    """)
    long sumManualReservedAndConfirmedForTask(
            @Param("taskId") Long taskId,
            @Param("paymentMethods") Collection<PaymentMethod> paymentMethods,
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
            @Param("activeAt") LocalDateTime activeAt,
            @Param("confirmedStatus") PaymentLinkStatus confirmedStatus,
            @Param("excludedLinkId") Long excludedLinkId
    );

    @Query("""
        SELECT COUNT(link)
        FROM PaymentLink link
        WHERE link.manualPaymentTask.id = :taskId
          AND link.paymentMethod IN :paymentMethods
          AND link.status IN :statuses
          AND link.expiresAt > :activeAt
    """)
    long countActiveManualPendingForTask(
            @Param("taskId") Long taskId,
            @Param("paymentMethods") Collection<PaymentMethod> paymentMethods,
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
            @Param("activeAt") LocalDateTime activeAt
    );

    @Query("""
        SELECT new com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryItem(
            COALESCE(link.manualRecipientName, ''),
            COALESCE(link.manualPhone, ''),
            COALESCE(link.manualPaymentUrl, ''),
            COALESCE(link.manualPaymentButtonLabel, ''),
            COALESCE(link.paymentProfileName, ''),
            link.manualSource,
            link.manualPaymentType,
            COUNT(link),
            COALESCE(SUM(CASE
                WHEN link.confirmedAmountKopecks IS NOT NULL THEN link.confirmedAmountKopecks
                WHEN link.reservedAmountKopecks IS NOT NULL THEN link.reservedAmountKopecks
                ELSE link.amountKopecks
            END), 0),
            MIN(COALESCE(link.manualConfirmedAt, link.paidAt)),
            MAX(COALESCE(link.manualConfirmedAt, link.paidAt))
        )
        FROM PaymentLink link
        WHERE link.status = :confirmedStatus
          AND link.paymentMethod IN :paymentMethods
          AND COALESCE(link.manualConfirmedAt, link.paidAt) >= :from
          AND COALESCE(link.manualConfirmedAt, link.paidAt) < :to
        GROUP BY link.manualRecipientName,
                 link.manualPhone,
                 link.manualPaymentUrl,
                 link.manualPaymentButtonLabel,
                 link.paymentProfileName,
                 link.manualSource,
                 link.manualPaymentType
        ORDER BY COALESCE(SUM(CASE
                WHEN link.confirmedAmountKopecks IS NOT NULL THEN link.confirmedAmountKopecks
                WHEN link.reservedAmountKopecks IS NOT NULL THEN link.reservedAmountKopecks
                ELSE link.amountKopecks
            END), 0) DESC
    """)
    List<ManualPaymentRecipientMonthlySummaryItem> summarizeManualConfirmedByRecipientForPeriod(
            @Param("paymentMethods") Collection<PaymentMethod> paymentMethods,
            @Param("confirmedStatus") PaymentLinkStatus confirmedStatus,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query(value = """
        SELECT receipt.sourceId AS sourceId,
               receipt.amountKopecks AS amountKopecks,
               receipt.effectiveAt AS effectiveAt
        FROM (
            SELECT link.id AS sourceId,
                   COALESCE(
                       link.confirmed_amount_kopecks,
                       link.reserved_amount_kopecks,
                       link.amount_kopecks
                   ) AS amountKopecks,
                   COALESCE(link.manual_confirmed_at, link.paid_at) AS effectiveAt
            FROM payment_links link
            WHERE link.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK')
              AND (
                  link.manual_confirmed_at IS NOT NULL
                  OR (link.status = 'CONFIRMED' AND link.paid_at IS NOT NULL)
              )
              AND COALESCE(link.manual_confirmed_at, link.paid_at) >= :from
              AND COALESCE(link.manual_confirmed_at, link.paid_at) < :to
              AND NOT EXISTS (
                  SELECT 1
                  FROM contractor_actual_payment_attributions attribution
                  WHERE (attribution.source_kind = 'PAYMENT_LINK' AND attribution.source_id = link.id)
                     OR attribution.evidence_id = link.id
              )
            UNION ALL
            SELECT archived.id AS sourceId,
                   COALESCE(
                       archived.confirmed_amount_kopecks,
                       archived.reserved_amount_kopecks,
                       archived.amount_kopecks
                   ) AS amountKopecks,
                   COALESCE(archived.manual_confirmed_at, archived.paid_at) AS effectiveAt
            FROM archive_payment_links archived
            WHERE archived.payment_method IN ('MANUAL_MOBILE_BANK', 'MANUAL_EXTERNAL_LINK')
              AND (
                  archived.manual_confirmed_at IS NOT NULL
                  OR (archived.status = 'CONFIRMED' AND archived.paid_at IS NOT NULL)
              )
              AND COALESCE(archived.manual_confirmed_at, archived.paid_at) >= :from
              AND COALESCE(archived.manual_confirmed_at, archived.paid_at) < :to
              AND NOT EXISTS (
                  SELECT 1 FROM payment_links live WHERE live.id = archived.id
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM contractor_actual_payment_attributions attribution
                  WHERE (attribution.source_kind = 'PAYMENT_LINK' AND attribution.source_id = archived.id)
                     OR attribution.evidence_id = archived.id
              )
        ) receipt
        ORDER BY receipt.effectiveAt, receipt.sourceId
    """, nativeQuery = true)
    List<ManualPaymentLegacyMonthlySourceProjection> findLegacyManualConfirmedForMonthlyRecipientSummary(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE PaymentLink link
        SET link.status = :expiredStatus,
            link.lastError = :reason,
            link.rowVersion = link.rowVersion + 1
        WHERE link.paymentMethod IN :paymentMethods
          AND link.status IN :statuses
          AND link.expiresAt <= :now
    """)
    int expireManualLinks(
            @Param("paymentMethods") Collection<PaymentMethod> paymentMethods,
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
            @Param("expiredStatus") PaymentLinkStatus expiredStatus,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT link FROM PaymentLink link
        WHERE link.paymentMethod IN :paymentMethods
          AND link.status IN :statuses
          AND link.expiresAt <= :now
        ORDER BY link.id
    """)
    List<PaymentLink> findExpiredManualLinksForUpdate(
            @Param("paymentMethods") Collection<PaymentMethod> paymentMethods,
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
            @Param("now") LocalDateTime now
    );

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.token = :token
    """)
    Optional<PaymentLink> findByTokenWithOrder(@Param("token") String token);

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        LEFT JOIN FETCH o.worker w
        LEFT JOIN FETCH w.user
        WHERE link.id = :id
    """)
    Optional<PaymentLink> findByIdWithOrder(@Param("id") Long id);

    /**
     * Finds public payment instructions created after the contractor-routing
     * rollout that have not received a shadow decision yet. The durable
     * payment_links row is the retry source when an afterCommit callback was
     * interrupted by a process restart or a temporary database error.
     */
    @Query(value = """
        SELECT link.id
        FROM payment_links link
        WHERE link.created_at >= :startedAt
          AND (
              link.shadow_route_generation IS NOT NULL
              OR link.created_at >= :preparationStartedAt
          )
          AND link.reserved_amount_kopecks IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_payment_allocations allocation
              WHERE allocation.mode = 'SHADOW'
                AND allocation.source_type = 'PAYMENT_LINK'
                AND allocation.source_id = link.id
                AND (
                    link.shadow_route_generation IS NULL
                    OR allocation.source_generation_snapshot = link.shadow_route_generation
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_shadow_backfill_claims claim
              WHERE claim.claim_key = CONCAT('PAYMENT_LINK:', link.id)
                AND (
                    claim.completed_at IS NOT NULL
                    OR claim.lease_until >= :now
                    OR claim.next_retry_at > :now
                )
          )
        ORDER BY link.created_at, link.id
    """, nativeQuery = true)
    List<Long> findMissingContractorShadowRouteIds(
            @Param("startedAt") LocalDateTime startedAt,
            @Param("preparationStartedAt") LocalDateTime preparationStartedAt,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM contractor_actual_payment_attributions attribution
            WHERE attribution.source_kind = 'PAYMENT_LINK'
              AND attribution.source_id = :originalLinkId
              AND attribution.evidence_id = :evidenceId
        )
    """, nativeQuery = true)
    boolean existsContractorActualPaymentAttribution(
            @Param("originalLinkId") Long originalLinkId,
            @Param("evidenceId") Long evidenceId
    );

    /**
     * Manual mobile-bank evidence is stored as a separate CONFIRMED payment
     * row while the original T-Bank row remains terminal/cancelled. This query
     * lets the contractor journal catch up if the immediate afterCommit hook
     * did not run.
     */
    @Query(value = """
        SELECT DISTINCT evidence.contractor_evidence_original_link_id AS originalLinkId,
               evidence.id AS evidenceLinkId,
               evidence.paid_at AS paidAt
        FROM payment_links evidence
        JOIN contractor_payment_allocations allocation
          ON (allocation.mode = 'LIVE' OR (:includeShadow = TRUE AND allocation.mode = 'SHADOW'))
         AND allocation.source_type = 'PAYMENT_LINK'
         AND allocation.source_id = evidence.contractor_evidence_original_link_id
         AND allocation.recipient_profile_id IS NOT NULL
         AND allocation.attempt_no = (
             SELECT MAX(latest.attempt_no)
             FROM contractor_payment_allocations latest
             WHERE latest.mode = allocation.mode
               AND latest.source_type = allocation.source_type
               AND latest.source_id = allocation.source_id
         )
        WHERE evidence.contractor_evidence_original_link_id IS NOT NULL
          AND evidence.updated_at >= :startedAt
          AND evidence.status = 'CONFIRMED'
          AND evidence.payment_method = 'MANUAL_MOBILE_BANK'
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_actual_payment_attributions attribution
              WHERE attribution.source_kind = 'PAYMENT_LINK'
                AND attribution.source_id = evidence.contractor_evidence_original_link_id
                AND attribution.evidence_id = evidence.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_payment_allocation_events event
              WHERE event.allocation_id = allocation.id
                AND event.external_ref = CONCAT('MANUAL_EVIDENCE:', evidence.id)
          )
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_shadow_backfill_claims claim
              WHERE claim.claim_key = CONCAT('MANUAL_EVIDENCE:', evidence.id)
                AND (
                    claim.completed_at IS NOT NULL
                    OR claim.lease_until >= :now
                    OR claim.next_retry_at > :now
                )
          )
        ORDER BY evidence.paid_at, evidence.id
    """, nativeQuery = true)
    List<ManualCardShadowEvidenceView> findUnrecordedContractorManualCardEvidence(
            @Param("startedAt") LocalDateTime startedAt,
            @Param("includeShadow") boolean includeShadow,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.tbankOrderId = :orderId
    """)
    Optional<PaymentLink> findByTbankOrderIdWithOrder(@Param("orderId") String orderId);

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.tbankPaymentId = :paymentId
    """)
    Optional<PaymentLink> findByTbankPaymentIdWithOrder(@Param("paymentId") String paymentId);

    @Query("""
        SELECT link
        FROM PaymentLink link
        LEFT JOIN FETCH link.paymentProfile
        LEFT JOIN FETCH link.order o
        LEFT JOIN FETCH o.company c
        LEFT JOIN FETCH c.manager cm
        LEFT JOIN FETCH cm.user
        LEFT JOIN FETCH cm.paymentProfile
        LEFT JOIN FETCH o.filial
        LEFT JOIN FETCH o.manager m
        LEFT JOIN FETCH m.user
        LEFT JOIN FETCH m.paymentProfile
        WHERE link.status = com.hunt.otziv.payments.model.PaymentLinkStatus.CONFIRMED
          AND link.paymentSuccessNotifiedAt IS NULL
          AND link.paymentSuccessNotificationRetryEligible = true
          AND (
            (LOWER(COALESCE(c.urlChat, '')) LIKE '%chat.whatsapp.com%'
              AND c.groupId IS NOT NULL
              AND TRIM(c.groupId) <> '')
            OR ((
                  LOWER(COALESCE(c.urlChat, '')) LIKE '%t.me/%'
                  OR LOWER(COALESCE(c.urlChat, '')) LIKE '%telegram.me/%'
                  OR LOWER(COALESCE(c.urlChat, '')) LIKE '%telegram.dog/%'
                  OR LOWER(COALESCE(c.urlChat, '')) LIKE 'tg://resolve?%'
                )
                AND c.telegramGroupChatId IS NOT NULL)
            OR (LOWER(COALESCE(c.urlChat, '')) LIKE '%max.ru%'
                AND c.maxGroupChatId IS NOT NULL)
          )
        ORDER BY link.updatedAt ASC, link.id ASC
    """)
    List<PaymentLink> findSuccessNotificationRetryCandidates(Pageable pageable);

    interface ManualCardShadowEvidenceView {
        Long getOriginalLinkId();

        Long getEvidenceLinkId();

        LocalDateTime getPaidAt();
    }
}
