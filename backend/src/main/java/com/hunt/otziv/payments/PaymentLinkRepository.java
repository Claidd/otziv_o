package com.hunt.otziv.payments;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentLinkRepository extends CrudRepository<PaymentLink, Long> {

    Optional<PaymentLink> findByToken(String token);

    List<PaymentLink> findTop100ByOrderByCreatedAtDesc();

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
