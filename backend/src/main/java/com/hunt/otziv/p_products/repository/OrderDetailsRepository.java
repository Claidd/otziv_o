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

    List<OrderDetails> findByOrderId(Long orderId);

    // OrderDetailsRepository
    @Modifying
    @Query("DELETE FROM OrderDetails od WHERE od.order.id = :orderId")
    int deleteByOrderId(@Param("orderId") Long orderId);
}
