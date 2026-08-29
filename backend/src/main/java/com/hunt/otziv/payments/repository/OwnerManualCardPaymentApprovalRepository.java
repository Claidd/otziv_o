package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OwnerManualCardPaymentApprovalRepository
        extends JpaRepository<OwnerManualCardPaymentApproval, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT approval FROM OwnerManualCardPaymentApproval approval WHERE approval.id = :id")
    Optional<OwnerManualCardPaymentApproval> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT approval FROM OwnerManualCardPaymentApproval approval WHERE approval.paymentLinkId = :linkId")
    Optional<OwnerManualCardPaymentApproval> findByPaymentLinkIdForUpdate(@Param("linkId") Long linkId);
}
