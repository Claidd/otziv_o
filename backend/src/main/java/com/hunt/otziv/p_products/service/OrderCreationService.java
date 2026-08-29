package com.hunt.otziv.p_products.service;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;

public interface OrderCreationService {
    boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO);
    boolean createRepeatedOrderWithReviews(Order sourceOrder, OrderDTO orderDTO);
    OrderDTO convertToOrderDTOToRepeat(Order order);
}
