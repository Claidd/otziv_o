package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.p_products.model.OrderDetails;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderDetailsRepository extends CrudRepository<OrderDetails, UUID> {
    @Override
    Optional<OrderDetails> findById(UUID orderDetailId);

}
