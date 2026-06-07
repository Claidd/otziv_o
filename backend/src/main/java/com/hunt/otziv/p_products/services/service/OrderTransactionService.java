package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.model.Order;

public interface OrderTransactionService {
    boolean handlePaymentStatus(Order order) throws Exception;

    boolean handlePaymentStatus(Order order, boolean createNextOrder) throws Exception;
}
