package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;

public interface OrderService {
    OrderDTO newOrderDTO(Long id);

    boolean addNewReview(Long orderId);
    boolean deleteNewReview(Long orderId, Long reviewId);


    boolean createNewOrderWithReviews(Long companyId, Long productId, OrderDTO orderDTO);

    boolean changeStatusForOrder(Long orderID, String title);

    OrderDTO getOrderDTO(Long orderId);
    Order getOrder(Long orderId);

    void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId);
//    Order getOrderById(Long orderId);
    boolean changeStatusAndOrderCounter(Long reviewId);

}
