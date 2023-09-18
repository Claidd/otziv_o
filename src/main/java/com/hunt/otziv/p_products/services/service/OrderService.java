package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;

public interface OrderService {
    OrderDTO newOrderDTO(Long id);

    boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO);

    boolean changeStatusForOrder(Long orderID, String title);

    OrderDTO getOrderDTO(Long orderId);

    void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId);
//    Order getOrderById(Long orderId);

}
