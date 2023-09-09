package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.p_products.model.Order;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {
}
