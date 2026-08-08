package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.u_users.model.Manager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonInvoiceRepository extends CrudRepository<CommonInvoice, Long> {

    @Query("""
        SELECT COUNT(item.id)
        FROM CommonInvoiceOrder item
        JOIN item.invoice invoice
        WHERE item.order.id = :orderId
          AND invoice.clientReportedAt IS NOT NULL
    """)
    long countClientReportedPaymentsByOrderId(@Param("orderId") Long orderId);

    /**
     * Durable retry source for common invoices whose payment route was frozen
     * but whose best-effort afterCommit shadow write was interrupted.
     */
    @Query(value = """
        SELECT invoice.invoice_id
        FROM common_invoices invoice
        WHERE invoice.payment_route_selected_at >= :startedAt
          AND (
              invoice.shadow_route_generation IS NOT NULL
              OR invoice.payment_route_selected_at >= :preparationStartedAt
          )
          AND COALESCE(invoice.payment_route_amount_kopecks, 0) > 0
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_payment_allocations allocation
              WHERE allocation.mode = 'SHADOW'
                AND allocation.source_type = 'COMMON_INVOICE'
                AND allocation.source_id = invoice.invoice_id
                AND (
                    invoice.shadow_route_generation IS NULL
                    OR allocation.source_generation_snapshot = invoice.shadow_route_generation
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM contractor_shadow_backfill_claims claim
              WHERE claim.claim_key = CONCAT('COMMON_INVOICE:', invoice.invoice_id)
                AND (
                    claim.completed_at IS NOT NULL
                    OR claim.lease_until >= :now
                    OR claim.next_retry_at > :now
                )
          )
        ORDER BY invoice.payment_route_selected_at, invoice.invoice_id
    """, nativeQuery = true)
    List<Long> findMissingContractorShadowRouteIds(
            @Param("startedAt") LocalDateTime startedAt,
            @Param("preparationStartedAt") LocalDateTime preparationStartedAt,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    Optional<CommonInvoice> findByToken(String token);

    @Query("""
        SELECT invoice.id AS invoiceId,
               invoice.contractorAllocationId AS allocationId
        FROM CommonInvoice invoice
        WHERE invoice.token = :token
    """)
    Optional<ContractorRouteRef> findContractorRouteRefByToken(@Param("token") String token);

    interface ContractorRouteRef {
        Long getInvoiceId();
        Long getAllocationId();
    }

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.id = :id
    """)
    Optional<CommonInvoice> findByIdWithAccount(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.id = :id
    """)
    Optional<CommonInvoice> findByIdWithAccountForUpdate(@Param("id") Long id);

    /** Source-only mutex for contractor accounting; does not join-lock account rows. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT invoice FROM CommonInvoice invoice WHERE invoice.id = :id")
    Optional<CommonInvoice> findByIdForUpdate(@Param("id") Long id);

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.token = :token
    """)
    Optional<CommonInvoice> findByTokenWithAccount(@Param("token") String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.token = :token
    """)
    Optional<CommonInvoice> findByTokenWithAccountForUpdate(@Param("token") String token);

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.account.id = :accountId
          AND invoice.status IN :statuses
        ORDER BY invoice.id DESC
    """)
    List<CommonInvoice> findCurrentForAccount(
            @Param("accountId") Long accountId,
            @Param("statuses") Collection<CommonInvoiceStatus> statuses,
            Pageable pageable
    );

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE account.id IN :accountIds
          AND invoice.status IN :statuses
          AND invoice.id = (
              SELECT MAX(candidate.id)
              FROM CommonInvoice candidate
              WHERE candidate.account.id = account.id
                AND candidate.status IN :statuses
          )
        ORDER BY account.id ASC
    """)
    List<CommonInvoice> findLatestCurrentForAccounts(
            @Param("accountIds") Collection<Long> accountIds,
            @Param("statuses") Collection<CommonInvoiceStatus> statuses
    );

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.status IN :statuses
          AND invoice.nextReminderAt IS NOT NULL
          AND invoice.nextReminderAt <= :now
        ORDER BY invoice.nextReminderAt ASC, invoice.id ASC
    """)
    List<CommonInvoice> findReminderCandidates(
            @Param("statuses") Collection<CommonInvoiceStatus> statuses,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        WHERE invoice.status IN :statuses
          AND account.enabled = true
          AND invoice.sentAt IS NULL
          AND invoice.updatedAt <= :readyBefore
          AND COALESCE(invoice.lastError, '') = ''
        ORDER BY invoice.updatedAt ASC, invoice.id ASC
    """)
    List<CommonInvoice> findUnsentActionCandidates(
            @Param("statuses") Collection<CommonInvoiceStatus> statuses,
            @Param("readyBefore") LocalDateTime readyBefore,
            Pageable pageable
    );

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.status IN :statuses
        ORDER BY invoice.updatedAt ASC, invoice.id ASC
    """)
    List<CommonInvoice> findBoardInvoices(@Param("statuses") Collection<CommonInvoiceStatus> statuses);

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.status IN :statuses
    """)
    List<CommonInvoice> findBoardInvoices(
            @Param("statuses") Collection<CommonInvoiceStatus> statuses,
            Pageable pageable
    );

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE invoice.id IN :invoiceIds
    """)
    List<CommonInvoice> findBoardInvoicesByIds(@Param("invoiceIds") Collection<Long> invoiceIds);

    @Query("""
        SELECT invoice.account.id
        FROM CommonInvoice invoice
        WHERE invoice.status IN :statuses
        GROUP BY invoice.account.id
        HAVING COUNT(invoice.id) > 1
        ORDER BY invoice.account.id ASC
    """)
    List<Long> findAccountIdsWithDuplicateCurrentInvoices(
            @Param("statuses") Collection<CommonInvoiceStatus> statuses
    );

    @Query("""
        SELECT COUNT(invoice.id)
        FROM CommonInvoice invoice
        JOIN invoice.account account
        LEFT JOIN account.invoiceCompany invoiceCompany
        WHERE (
            account.manager = :manager
            OR invoiceCompany.manager = :manager
            OR EXISTS (
                SELECT invoiceOrder.id
                FROM CommonInvoiceOrder invoiceOrder
                JOIN invoiceOrder.order linkedOrder
                WHERE invoiceOrder.invoice = invoice
                  AND linkedOrder.manager = :manager
            )
        )
          AND LOWER(COALESCE(invoice.lastError, '')) NOT LIKE 'review_recovery_active:%'
          AND (
            invoice.status IN :criticalStatuses
            OR (
              invoice.status = :partiallyPaidStatus
              AND (invoice.sentAt IS NULL OR invoice.nextReminderAt IS NULL)
            )
            OR (
              invoice.status IN :staleStatuses
              AND invoice.updatedAt <= :staleBefore
              AND (
                (
                  invoice.status <> :partiallyPaidStatus
                  AND invoice.status <> :collectingStatus
                )
                OR NOT EXISTS (
                  SELECT pendingInvoiceOrder.id
                  FROM CommonInvoiceOrder pendingInvoiceOrder
                  WHERE pendingInvoiceOrder.invoice = invoice
                    AND pendingInvoiceOrder.ready = false
                )
              )
            )
            OR COALESCE(invoice.lastError, '') <> ''
            OR COALESCE(invoice.paymentSuccessNotificationError, '') <> ''
            OR (
              invoice.status = :collectingStatus
              AND EXISTS (
              SELECT publicationBlocker.id
              FROM CommonInvoiceOrder publicationBlocker
              WHERE publicationBlocker.invoice = invoice
                AND publicationBlocker.publicationBlockerSince IS NOT NULL
                AND publicationBlocker.publicationBlockerSince <= :publicationBlockerBefore
              )
            )
          )
    """)
    long countManagerControlInvoices(
            @Param("manager") Manager manager,
            @Param("criticalStatuses") Collection<CommonInvoiceStatus> criticalStatuses,
            @Param("staleStatuses") Collection<CommonInvoiceStatus> staleStatuses,
            @Param("partiallyPaidStatus") CommonInvoiceStatus partiallyPaidStatus,
            @Param("collectingStatus") CommonInvoiceStatus collectingStatus,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("publicationBlockerBefore") LocalDateTime publicationBlockerBefore
    );

    @Query("""
        SELECT invoice
        FROM CommonInvoice invoice
        JOIN FETCH invoice.account account
        LEFT JOIN FETCH account.manager manager
        LEFT JOIN FETCH manager.user
        LEFT JOIN FETCH account.invoiceCompany invoiceCompany
        LEFT JOIN FETCH invoiceCompany.manager invoiceManager
        LEFT JOIN FETCH invoiceManager.user
        WHERE (
            account.manager = :manager
            OR invoiceCompany.manager = :manager
            OR EXISTS (
                SELECT invoiceOrder.id
                FROM CommonInvoiceOrder invoiceOrder
                JOIN invoiceOrder.order linkedOrder
                WHERE invoiceOrder.invoice = invoice
                  AND linkedOrder.manager = :manager
            )
        )
          AND LOWER(COALESCE(invoice.lastError, '')) NOT LIKE 'review_recovery_active:%'
          AND (
            invoice.status IN :criticalStatuses
            OR (
              invoice.status = :partiallyPaidStatus
              AND (invoice.sentAt IS NULL OR invoice.nextReminderAt IS NULL)
            )
            OR (
              invoice.status IN :staleStatuses
              AND invoice.updatedAt <= :staleBefore
              AND (
                (
                  invoice.status <> :partiallyPaidStatus
                  AND invoice.status <> :collectingStatus
                )
                OR NOT EXISTS (
                  SELECT pendingInvoiceOrder.id
                  FROM CommonInvoiceOrder pendingInvoiceOrder
                  WHERE pendingInvoiceOrder.invoice = invoice
                    AND pendingInvoiceOrder.ready = false
                )
              )
            )
            OR COALESCE(invoice.lastError, '') <> ''
            OR COALESCE(invoice.paymentSuccessNotificationError, '') <> ''
            OR (
              invoice.status = :collectingStatus
              AND EXISTS (
              SELECT publicationBlocker.id
              FROM CommonInvoiceOrder publicationBlocker
              WHERE publicationBlocker.invoice = invoice
                AND publicationBlocker.publicationBlockerSince IS NOT NULL
                AND publicationBlocker.publicationBlockerSince <= :publicationBlockerBefore
              )
            )
          )
        ORDER BY invoice.updatedAt ASC, invoice.id ASC
    """)
    List<CommonInvoice> findManagerControlInvoices(
            @Param("manager") Manager manager,
            @Param("criticalStatuses") Collection<CommonInvoiceStatus> criticalStatuses,
            @Param("staleStatuses") Collection<CommonInvoiceStatus> staleStatuses,
            @Param("partiallyPaidStatus") CommonInvoiceStatus partiallyPaidStatus,
            @Param("collectingStatus") CommonInvoiceStatus collectingStatus,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("publicationBlockerBefore") LocalDateTime publicationBlockerBefore,
            Pageable pageable
    );

    Optional<CommonInvoice> findByTbankOrderId(String tbankOrderId);

    Optional<CommonInvoice> findByTbankPaymentId(String tbankPaymentId);

    @Query("""
        SELECT invoice.id
        FROM CommonInvoice invoice
        WHERE invoice.tbankOrderId = :tbankOrderId
        ORDER BY invoice.id ASC
    """)
    List<Long> findIdsByTbankOrderId(@Param("tbankOrderId") String tbankOrderId);

    @Query("""
        SELECT invoice.id
        FROM CommonInvoice invoice
        WHERE invoice.tbankPaymentId = :tbankPaymentId
        ORDER BY invoice.id ASC
    """)
    List<Long> findIdsByTbankPaymentId(@Param("tbankPaymentId") String tbankPaymentId);

    @Query("""
        SELECT COALESCE(SUM(invoice.paymentRouteAmountKopecks), 0)
        FROM CommonInvoice invoice
        WHERE invoice.paymentRouteManualTaskId = :taskId
          AND invoice.paymentRouteSelectedAt IS NOT NULL
          AND invoice.paymentRouteAmountKopecks > 0
          AND (invoice.status IN :activeStatuses OR invoice.status = :paidStatus)
    """)
    long sumReservedAndConfirmedPaymentRouteForTask(
            @Param("taskId") Long taskId,
            @Param("activeStatuses") Collection<CommonInvoiceStatus> activeStatuses,
            @Param("paidStatus") CommonInvoiceStatus paidStatus
    );

    @Query("""
        SELECT COALESCE(SUM(invoice.paymentRouteAmountKopecks), 0)
        FROM CommonInvoice invoice
        WHERE invoice.paymentRouteManualTaskId = :taskId
          AND invoice.paymentRouteSelectedAt IS NOT NULL
          AND invoice.paymentRouteAmountKopecks > 0
          AND invoice.paidKopecks >= invoice.amountKopecks
    """)
    long sumConfirmedPaymentRouteForTask(@Param("taskId") Long taskId);

    @Query("""
        SELECT COUNT(invoice)
        FROM CommonInvoice invoice
        WHERE invoice.paymentRouteManualTaskId = :taskId
          AND invoice.paymentRouteSelectedAt IS NOT NULL
          AND invoice.paymentRouteAmountKopecks > 0
          AND invoice.status IN :activeStatuses
          AND invoice.paidKopecks < invoice.amountKopecks
    """)
    long countActivePaymentRoutesForTask(
            @Param("taskId") Long taskId,
            @Param("activeStatuses") Collection<CommonInvoiceStatus> activeStatuses
    );

    @Query("""
        SELECT COALESCE(SUM(invoice.paymentRouteAmountKopecks), 0)
        FROM CommonInvoice invoice
        WHERE invoice.paymentRouteProfileId = :profileId
          AND invoice.paymentRouteManualSource = :manualSource
          AND invoice.paymentRouteSelectedAt >= :from
          AND invoice.paymentRouteSelectedAt < :to
          AND invoice.paymentRouteAmountKopecks > 0
          AND (invoice.status IN :activeStatuses OR invoice.status = :paidStatus)
    """)
    long sumReservedAndConfirmedProfilePaymentRoutesForPeriod(
            @Param("profileId") Long profileId,
            @Param("manualSource") ManualPaymentSource manualSource,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("activeStatuses") Collection<CommonInvoiceStatus> activeStatuses,
            @Param("paidStatus") CommonInvoiceStatus paidStatus
    );
}
