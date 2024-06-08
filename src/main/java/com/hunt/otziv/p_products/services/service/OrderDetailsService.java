package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.OrderDetails;

import java.util.UUID;

public interface OrderDetailsService {

    OrderDetails save(OrderDetails orderDetails);

    OrderDetailsDTO getOrderDetailDTOById(UUID orderDetailId);
    OrderDetails getOrderDetailById(UUID orderDetailId);

    void deleteOrderDetailsById(UUID orderDetailId);

    void deleteOrderDetails(OrderDetails orderDetails);
}
