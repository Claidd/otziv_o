package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.p_products.model.OrderDetails;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderDetailsRepository extends CrudRepository<OrderDetails, UUID> {
    @Override
    Optional<OrderDetails> findById(UUID orderDetailId);

    @Query("""
            SELECT DISTINCT od
            FROM OrderDetails od
            LEFT JOIN FETCH od.product
            LEFT JOIN FETCH od.order o
            LEFT JOIN FETCH o.company
            LEFT JOIN FETCH o.filial
            LEFT JOIN FETCH o.status
            LEFT JOIN FETCH o.worker ow
            LEFT JOIN FETCH ow.user
            LEFT JOIN FETCH o.manager om
            LEFT JOIN FETCH om.user
            LEFT JOIN FETCH od.reviews r
            LEFT JOIN FETCH r.product
            LEFT JOIN FETCH r.bot
            LEFT JOIN FETCH r.worker rw
            LEFT JOIN FETCH rw.user
            WHERE od.id = :orderDetailId
            """)
    Optional<OrderDetails> findByIdForReviewCheck(@Param("orderDetailId") UUID orderDetailId);

    @Query("""
            SELECT DISTINCT od
            FROM OrderDetails od
            LEFT JOIN FETCH od.product
            LEFT JOIN FETCH od.reviews
            WHERE od.order.id = :orderId
            """)
    List<OrderDetails> findAllByOrderIdForOrderDto(@Param("orderId") Long orderId);

    List<OrderDetails> findByOrderId(Long orderId);

    // OrderDetailsRepository
    @Modifying
    @Query("DELETE FROM OrderDetails od WHERE od.order.id = :orderId")
    int deleteByOrderId(@Param("orderId") Long orderId);
}
