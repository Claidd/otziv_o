package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.p_products.model.OrderStatus;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderStatusRepository extends CrudRepository<OrderStatus, Long> {

    Optional<OrderStatus> findByTitle(String title);
}
