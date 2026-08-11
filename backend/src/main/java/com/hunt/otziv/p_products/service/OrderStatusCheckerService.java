package com.hunt.otziv.p_products.service;

import com.hunt.otziv.p_products.model.Order;

public interface OrderStatusCheckerService {
    void validateCounterConsistency(Order order, int actualPublished);
    void checkAndMarkOrderCompleted(Order order) throws Exception;
}
