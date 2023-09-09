package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.OrderDTO;

public interface OrderService {
    OrderDTO newOrderDTO(Long id);

    boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO);
}
