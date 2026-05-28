package com.hunt.otziv.payments;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentLinkRepository extends JpaRepository<PaymentLink, Long> {

    Optional<PaymentLink> findByToken(String token);

    List<PaymentLink> findTop100ByOrderByCreatedAtDesc();

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
                    OR (:statusFilter = 'created' AND link.status = com.hunt.otziv.payments.PaymentLinkStatus.CREATED)
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
                    OR (:statusFilter = 'created' AND link.status = com.hunt.otziv.payments.PaymentLinkStatus.CREATED)
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
        SELECT new com.hunt.otziv.payments.PaymentLinkAdminSummary(
            COUNT(link),
            COALESCE(SUM(link.amountKopecks), 0),
            COALESCE(SUM(CASE WHEN link.status IN :paidStatuses THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.paymentMethod IN :manualMethods AND link.status IN :manualPendingStatuses THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status = com.hunt.otziv.payments.PaymentLinkStatus.CONFIRMED THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status = com.hunt.otziv.payments.PaymentLinkStatus.CONFIRMED AND link.paymentSuccessNotifiedAt IS NOT NULL THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status = com.hunt.otziv.payments.PaymentLinkStatus.CONFIRMED AND link.paymentSuccessNotifiedAt IS NULL AND link.paymentSuccessNotificationError IS NOT NULL THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status IN :refundableStatuses AND link.tbankPaymentId IS NOT NULL AND link.tbankPaymentId <> '' THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status IN :refundedStatuses THEN 1 ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN link.status IN :rejectedStatuses THEN 1 ELSE 0 END), 0)
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
            OR (:statusFilter = 'created' AND link.status = com.hunt.otziv.payments.PaymentLinkStatus.CREATED)
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
            @Param("rejectedStatuses") Collection<PaymentLinkStatus> rejectedStatuses
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

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN link.confirmedAmountKopecks IS NOT NULL THEN link.confirmedAmountKopecks
            WHEN link.reservedAmountKopecks IS NOT NULL THEN link.reservedAmountKopecks
            ELSE link.amountKopecks
        END), 0)
        FROM PaymentLink link
        WHERE link.paymentProfile.id = :profileId
          AND link.paymentMethod IN :paymentMethods
          AND (link.manualSource IS NULL OR link.manualSource = com.hunt.otziv.payments.ManualPaymentSource.PROFILE_MONTHLY_LIMIT)
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
          AND (link.manualSource IS NULL OR link.manualSource = com.hunt.otziv.payments.ManualPaymentSource.PROFILE_MONTHLY_LIMIT)
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE PaymentLink link
        SET link.status = :expiredStatus,
            link.lastError = :reason
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
}
