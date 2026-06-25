package com.hunt.otziv.common_billing.repository;

import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommonInvoicePaymentRefRepository extends CrudRepository<CommonInvoicePaymentRef, Long> {

    Optional<CommonInvoicePaymentRef> findByTbankOrderId(String tbankOrderId);

    Optional<CommonInvoicePaymentRef> findByTbankPaymentId(String tbankPaymentId);

    @Query("""
            select ref
            from CommonInvoicePaymentRef ref
            where ref.status = :pendingStatus
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
            order by ref.updatedAt asc, ref.id asc
            """)
    List<CommonInvoicePaymentRef> findCancelableRefs(
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
}
