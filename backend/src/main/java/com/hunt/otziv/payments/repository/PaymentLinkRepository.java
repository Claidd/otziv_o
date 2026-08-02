package com.hunt.otziv.payments.repository;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT link FROM PaymentLink link WHERE link.order.id = :orderId ORDER BY link.id")
    List<PaymentLink> findByOrderIdForUpdate(@Param("orderId") Long orderId);

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
            @Param("receiptOverdueBefore") LocalDateTime receiptOverdueBefore
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

    Optional<PaymentLink> findFirstByOrder_IdAndStatusAndLastErrorIsNullOrderByPaidAtDesc(
            Long orderId,
            PaymentLinkStatus status
    );

    @Query("""
        SELECT link.id
        FROM PaymentLink link
        WHERE (link.status IN :statuses OR link.bankCancelOriginStatus IS NOT NULL)
          AND link.tbankPaymentId IS NOT NULL
          AND TRIM(link.tbankPaymentId) <> ''
          AND link.updatedAt <= :updatedBefore
          AND (
              link.bankReconciliationAttemptedAt IS NULL
              OR link.bankReconciliationAttemptedAt <= :attemptBefore
          )
        ORDER BY link.bankReconciliationAttemptedAt ASC, link.updatedAt ASC, link.id ASC
    """)
    List<Long> findBankReconciliationCandidateIds(
            @Param("statuses") Collection<PaymentLinkStatus> statuses,
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
        WHERE link.id = :id
    """)
    Optional<PaymentLink> findByIdWithOrder(@Param("id") Long id);

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
}
