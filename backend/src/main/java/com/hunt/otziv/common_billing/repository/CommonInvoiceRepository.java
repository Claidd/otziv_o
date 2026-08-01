package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
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

    Optional<CommonInvoice> findByToken(String token);

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
        WHERE invoice.account.id = :accountId
          AND invoice.status IN :statuses
        ORDER BY invoice.id DESC
    """)
    List<CommonInvoice> findCurrentForAccountForUpdate(
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
}
