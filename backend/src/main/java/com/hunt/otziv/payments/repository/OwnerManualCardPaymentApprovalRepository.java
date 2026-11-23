package com.hunt.otziv.payments.repository;

import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OwnerManualCardPaymentApprovalRepository
        extends JpaRepository<OwnerManualCardPaymentApproval, Long> {

    /** Scalar snapshot avoids caching a stale managed approval before taking canonical locks. */
    @Query("SELECT approval.orderId AS orderId, approval.paymentLinkId AS paymentLinkId, "
            + "approval.callbackTokenHash AS callbackTokenHash "
            + "FROM OwnerManualCardPaymentApproval approval WHERE approval.id = :id")
    Optional<ApprovalBinding> findBindingById(@Param("id") Long id);

    interface ApprovalBinding {
        Long getOrderId();
        Long getPaymentLinkId();
        String getCallbackTokenHash();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT approval FROM OwnerManualCardPaymentApproval approval WHERE approval.id = :id")
    Optional<OwnerManualCardPaymentApproval> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT approval FROM OwnerManualCardPaymentApproval approval WHERE approval.paymentLinkId = :linkId")
    Optional<OwnerManualCardPaymentApproval> findByPaymentLinkIdForUpdate(@Param("linkId") Long linkId);

    Optional<OwnerManualCardPaymentApproval> findByPaymentLinkId(Long paymentLinkId);

    /** Requests waiting for the owner, oldest first («Требует решения»). */
    List<OwnerManualCardPaymentApproval> findByStatusOrderByRequestedAtAscIdAsc(
            OwnerManualCardPaymentApprovalStatus status, Pageable page);

    /** Pending requests of orders already in «Оплачено»: candidates for the automatic stale-request check. */
    @Query("SELECT approval.id FROM OwnerManualCardPaymentApproval approval, Order paidOrder "
            + "WHERE paidOrder.id = approval.orderId AND approval.status = :status "
            + "AND paidOrder.status.title = 'Оплачено' ORDER BY approval.id")
    List<Long> findIdsOnPaidOrders(@Param("status") OwnerManualCardPaymentApprovalStatus status, Pageable page);
}
