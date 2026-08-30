package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.payments.repository.ManualPaymentLegacyMonthlySourceProjection;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
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

    Optional<CommonInvoice> findByCycleIdempotencyKey(String cycleIdempotencyKey);

    boolean existsBySupersedesInvoice_Id(Long invoiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT invoice FROM CommonInvoice invoice WHERE invoice.supersedesInvoice.id = :invoiceId ORDER BY invoice.id DESC")
    List<CommonInvoice> findSuccessorsForUpdate(@Param("invoiceId") Long invoiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT invoice FROM CommonInvoice invoice WHERE invoice.cycleIdempotencyKey = :key")
    Optional<CommonInvoice> findByCycleIdempotencyKeyForUpdate(@Param("key") String key);

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
          AND invoice.invoicePurpose = 'STANDARD'
          AND (invoice.paymentRouteSelectedAt IS NULL
               OR COALESCE(TRIM(invoice.paymentRouteType), '') = '')
        ORDER BY invoice.id DESC
    """)
    List<CommonInvoice> findCurrentForAccount(
            @Param("accountId") Long accountId,
            @Param("statuses") Collection<CommonInvoiceStatus> statuses,
            Pageable pageable
    );

    /** Current financial presentation for account UI; unlike the attach query,
     * this also returns a bad-review successor. */
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
        ORDER BY CASE WHEN invoice.invoicePurpose = 'BAD_REVIEW_SUCCESSOR' THEN 0 ELSE 1 END,
                 invoice.id DESC
    """)
    List<CommonInvoice> findCurrentPresentationForAccount(
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
          AND (
              (
                  invoice.invoicePurpose = 'BAD_REVIEW_SUCCESSOR'
                  AND invoice.id = (
                      SELECT MAX(successor.id)
                      FROM CommonInvoice successor
                      WHERE successor.account.id = account.id
                        AND successor.status IN :statuses
                        AND successor.invoicePurpose = 'BAD_REVIEW_SUCCESSOR'
                  )
              )
              OR (
                  NOT EXISTS (
                      SELECT currentSuccessor.id
                      FROM CommonInvoice currentSuccessor
                      WHERE currentSuccessor.account.id = account.id
                        AND currentSuccessor.status IN :statuses
                        AND currentSuccessor.invoicePurpose = 'BAD_REVIEW_SUCCESSOR'
                  )
                  AND invoice.id = (
                      SELECT MAX(candidate.id)
                      FROM CommonInvoice candidate
                      WHERE candidate.account.id = account.id
                        AND candidate.status IN :statuses
                  )
              )
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
        SELECT invoice.id
        FROM CommonInvoice invoice
        WHERE invoice.invoicePaymentMode = :paperInvoiceMode
          AND invoice.sentAt IS NOT NULL
          AND invoice.paperInvoiceIssuedAt IS NULL
          AND invoice.status IN :statuses
        ORDER BY invoice.sentAt ASC, invoice.id ASC
    """)
    List<Long> findPaperInvoiceDeliveryNotificationCandidates(
            @Param("paperInvoiceMode") InvoicePaymentMode paperInvoiceMode,
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
          AND invoice.invoicePurpose = 'STANDARD'
          AND (invoice.paymentRouteSelectedAt IS NULL
               OR COALESCE(TRIM(invoice.paymentRouteType), '') = '')
        GROUP BY invoice.account.id
        HAVING COUNT(invoice.id) > 1
        ORDER BY invoice.account.id ASC
    """)
    List<Long> findAccountIdsWithDuplicateCurrentInvoices(
            @Param("statuses") Collection<CommonInvoiceStatus> statuses
    );

    @Query("""
        SELECT DISTINCT invoice.id
        FROM CommonInvoice invoice
        WHERE invoice.status IN :statuses
          AND invoice.invoicePurpose = 'STANDARD'
          AND invoice.paymentRouteSelectedAt IS NOT NULL
          AND COALESCE(TRIM(invoice.paymentRouteType), '') <> ''
          AND (
            invoice.account.id = :targetAccountId
            OR EXISTS (
                SELECT item.id
                FROM CommonInvoiceOrder item
                JOIN item.order linkedOrder
                WHERE item.invoice = invoice
                  AND item.activeMembership = TRUE
                  AND item.paid = FALSE
                  AND item.unpaid = FALSE
                  AND linkedOrder.company.id = :companyId
            )
          )
        ORDER BY invoice.id ASC
    """)
    List<Long> findFrozenCompositionInvoiceIdsForCompanyReconcile(
            @Param("companyId") Long companyId,
            @Param("targetAccountId") Long targetAccountId,
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
            OR (
              invoice.invoicePaymentMode = :paperInvoiceMode
              AND invoice.sentAt IS NOT NULL
              AND invoice.paperInvoiceIssuedAt IS NULL
              AND invoice.sentAt <= :paperInvoiceDeliveryBefore
              AND invoice.status IN :paperInvoiceOpenStatuses
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
            @Param("publicationBlockerBefore") LocalDateTime publicationBlockerBefore,
            @Param("paperInvoiceMode") InvoicePaymentMode paperInvoiceMode,
            @Param("paperInvoiceOpenStatuses") Collection<CommonInvoiceStatus> paperInvoiceOpenStatuses,
            @Param("paperInvoiceDeliveryBefore") LocalDateTime paperInvoiceDeliveryBefore
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
            OR (
              invoice.invoicePaymentMode = :paperInvoiceMode
              AND invoice.sentAt IS NOT NULL
              AND invoice.paperInvoiceIssuedAt IS NULL
              AND invoice.sentAt <= :paperInvoiceDeliveryBefore
              AND invoice.status IN :paperInvoiceOpenStatuses
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
            @Param("paperInvoiceMode") InvoicePaymentMode paperInvoiceMode,
            @Param("paperInvoiceOpenStatuses") Collection<CommonInvoiceStatus> paperInvoiceOpenStatuses,
            @Param("paperInvoiceDeliveryBefore") LocalDateTime paperInvoiceDeliveryBefore,
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

    @Query(value = """
        SELECT receipt.sourceId AS sourceId,
               SUM(receipt.amountKopecks) AS amountKopecks,
               MAX(receipt.effectiveAt) AS effectiveAt
        FROM (
            SELECT invoice.invoice_id AS sourceId,
                   item.amount_kopecks AS amountKopecks,
                   item.paid_at AS effectiveAt
            FROM common_invoice_orders item
            JOIN common_invoices invoice ON invoice.invoice_id = item.invoice_id
            WHERE item.paid = 1
              AND item.paid_at >= :from
              AND item.paid_at < :to
              AND item.source_payment_link_id IS NULL
              AND item.actual_payment_evidence_reference IS NULL
              AND (
                  UPPER(COALESCE(item.payment_method, '')) IN ('MANUAL', 'MANUAL_LEGACY')
                  OR COALESCE(item.manual_paid_by, '') <> ''
                  OR COALESCE(item.manual_payment_comment, '') <> ''
                  OR COALESCE(item.manual_payment_receipt_url, '') <> ''
                  OR (
                      COALESCE(item.payment_method, '') = ''
                      AND UPPER(COALESCE(invoice.payment_method, '')) = 'MANUAL'
                  )
              )
            UNION ALL
            SELECT archived_invoice.invoice_id AS sourceId,
                   archived_item.amount_kopecks AS amountKopecks,
                   archived_item.paid_at AS effectiveAt
            FROM archive_common_invoice_orders archived_item
            JOIN archive_common_invoices archived_invoice
              ON archived_invoice.invoice_id = archived_item.invoice_id
            WHERE archived_invoice.restored_at IS NULL
              AND archived_item.paid = 1
              AND archived_item.paid_at >= :from
              AND archived_item.paid_at < :to
              AND archived_item.source_payment_link_id IS NULL
              AND archived_item.actual_payment_evidence_reference IS NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM common_invoice_orders live_item
                  WHERE live_item.invoice_order_id = archived_item.invoice_order_id
              )
              AND (
                  UPPER(COALESCE(archived_item.payment_method, '')) IN ('MANUAL', 'MANUAL_LEGACY')
                  OR COALESCE(archived_item.manual_paid_by, '') <> ''
                  OR COALESCE(archived_item.manual_payment_comment, '') <> ''
                  OR COALESCE(archived_item.manual_payment_receipt_url, '') <> ''
                  OR (
                      COALESCE(archived_item.payment_method, '') = ''
                      AND UPPER(COALESCE(archived_invoice.payment_method, '')) = 'MANUAL'
                  )
              )
        ) receipt
        GROUP BY receipt.sourceId
        ORDER BY MAX(receipt.effectiveAt), receipt.sourceId
    """, nativeQuery = true)
    List<ManualPaymentLegacyMonthlySourceProjection> findLegacyManualConfirmedForMonthlyRecipientSummary(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
}
