package com.hunt.otziv.p_products.next_order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface NextOrderRequestRepository extends CrudRepository<NextOrderRequest, Long> {

    Optional<NextOrderRequest> findBySourceOrderId(Long sourceOrderId);

    List<NextOrderRequest> findByCreatedOrder_Id(Long createdOrderId);

    boolean existsByCompanyIdAndStatusIn(Long companyId, Collection<NextOrderRequestStatus> statuses);

    @Query("""
        SELECT r
        FROM NextOrderRequest r
        JOIN FETCH r.company c
        LEFT JOIN FETCH r.filial
        WHERE c.id IN :companyIds
          AND r.status IN :statuses
    """)
    List<NextOrderRequest> findByCompanyIdInAndStatusIn(@Param("companyIds") Collection<Long> companyIds,
                                                        @Param("statuses") Collection<NextOrderRequestStatus> statuses);

    @Query("""
        SELECT r
        FROM NextOrderRequest r
        JOIN r.sourceOrder sourceOrder
        LEFT JOIN sourceOrder.worker sourceWorker
        WHERE r.company.id = :companyId
          AND ((:filialId IS NULL AND r.filial IS NULL) OR (:filialId IS NOT NULL AND r.filial.id = :filialId))
          AND (:workerId IS NULL OR sourceWorker.id = :workerId)
          AND r.status IN :statuses
        ORDER BY r.updatedAt DESC, r.id DESC
    """)
    List<NextOrderRequest> findOpenByCompanyIdAndFilialId(@Param("companyId") Long companyId,
                                                          @Param("filialId") Long filialId,
                                                          @Param("workerId") Long workerId,
                                                          @Param("statuses") Collection<NextOrderRequestStatus> statuses,
                                                          Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM NextOrderRequest request
        WHERE request.sourceOrder.id = :orderId
    """)
    int deleteBySourceOrderId(@Param("orderId") Long orderId);
}
