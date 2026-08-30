package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import jakarta.persistence.LockModeType;
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
public interface CommonInvoicePaymentRefRepository extends CrudRepository<CommonInvoicePaymentRef, Long> {

    Optional<CommonInvoicePaymentRef> findByTbankOrderId(String tbankOrderId);

    Optional<CommonInvoicePaymentRef> findByTbankPaymentId(String tbankPaymentId);

    Optional<CommonInvoicePaymentRef> findByProviderAndProviderOrderId(
            String provider,
            String providerOrderId
    );

    Optional<CommonInvoicePaymentRef> findByProviderAndProviderPaymentId(
            String provider,
            String providerPaymentId
    );

    /** Read-only parent discovery used before taking the payment-ref lock. */
    @Query("""
            select ref.invoice.id
            from CommonInvoicePaymentRef ref
            where ref.id = :id
            """)
    Optional<Long> findInvoiceIdById(@Param("id") Long id);

    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            join fetch ref.invoice invoice
            where (
                    ref.provider is null
                    or trim(ref.provider) = ''
                    or upper(ref.provider) = upper(:provider)
                  )
              and (
                    ref.status = :pendingStatus
               or (
                    ref.status = :legacyConflictStatus
                    and ref.tbankPaymentId is not null
                    and ref.tbankTerminalKey is not null
                    and ref.amountKopecks is not null
                    and ref.amountKopecks > 0
               )
               or (
                    ref.status = :failedStatus
                    and ref.updatedAt <= :failedBefore
                    and coalesce(ref.cancelAttempts, 0) < :maxAttempts
               )
               or (
                    ref.status = :cancelingStatus
                    and ref.updatedAt <= :cancelingBefore
               )
              )
            order by ref.updatedAt asc, ref.id asc
            """)
    List<CommonInvoicePaymentRef> findCancelableRefs(
            @Param("provider") String provider,
            @Param("pendingStatus") String pendingStatus,
            @Param("failedStatus") String failedStatus,
            @Param("legacyConflictStatus") String legacyConflictStatus,
            @Param("cancelingStatus") String cancelingStatus,
            @Param("failedBefore") java.time.LocalDateTime failedBefore,
            @Param("cancelingBefore") java.time.LocalDateTime cancelingBefore,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            where ref.id = :id
            """)
    Optional<CommonInvoicePaymentRef> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            where ref.invoice.id = :invoiceId
              and ref.status = :status
            order by ref.updatedAt desc
            """)
    List<CommonInvoicePaymentRef> findByInvoiceIdAndStatusForUpdate(
            @Param("invoiceId") Long invoiceId,
            @Param("status") String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            where ref.invoice.id = :invoiceId
            order by ref.createdAt asc, ref.id asc
            """)
    List<CommonInvoicePaymentRef> findByInvoiceIdForUpdate(@Param("invoiceId") Long invoiceId);

    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            where ref.invoice.id = :invoiceId
            order by ref.createdAt asc, ref.id asc
            """)
    List<CommonInvoicePaymentRef> findByInvoiceIdOrderByCreatedAtAsc(
            @Param("invoiceId") Long invoiceId
    );

    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            join fetch ref.invoice invoice
            where ref.provider = :provider
              and ref.status in :statuses
              and ref.updatedAt <= :updatedBefore
            order by ref.updatedAt asc, ref.id asc
            """)
    List<CommonInvoicePaymentRef> findProviderReconciliationCandidates(
            @Param("provider") String provider,
            @Param("statuses") java.util.Collection<String> statuses,
            @Param("updatedBefore") java.time.LocalDateTime updatedBefore,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            where ref.invoice.id = :invoiceId
              and ref.provider = :provider
              and ref.status = :status
            order by ref.updatedAt desc, ref.id desc
            """)
    List<CommonInvoicePaymentRef> findCurrentProviderRefsForUpdate(
            @Param("invoiceId") Long invoiceId,
            @Param("provider") String provider,
            @Param("status") String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            where ref.invoice.id = :invoiceId
              and ref.provider = :provider
              and ref.status in :statuses
            order by ref.updatedAt desc, ref.id desc
            """)
    List<CommonInvoicePaymentRef> findProviderRefsForUpdate(
            @Param("invoiceId") Long invoiceId,
            @Param("provider") String provider,
            @Param("statuses") java.util.Collection<String> statuses
    );

    @Query("""
            select coalesce(sum(ref.amountKopecks), 0)
            from CommonInvoicePaymentRef ref
            where ref.invoice.id = :invoiceId
              and ref.status = :status
            """)
    long sumAmountKopecksByInvoiceIdAndStatus(
            @Param("invoiceId") Long invoiceId,
            @Param("status") String status
    );

    boolean existsByInvoice_Id(Long invoiceId);

    /** Current read used after the parent invoice mutex is held. */
    @Query(value = """
        SELECT payment_ref.payment_ref_id
        FROM common_invoice_payment_refs payment_ref
        WHERE payment_ref.invoice_id = :invoiceId
        ORDER BY payment_ref.payment_ref_id
        FOR UPDATE
    """, nativeQuery = true)
    List<Long> findIdsByInvoiceIdForUpdate(@Param("invoiceId") Long invoiceId);

    boolean existsByInvoice_IdAndStatusIn(Long invoiceId, java.util.Collection<String> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from CommonInvoicePaymentRef ref
            where ref.invoice.id = :invoiceId
            """)
    int deleteByInvoiceId(@Param("invoiceId") Long invoiceId);
}
