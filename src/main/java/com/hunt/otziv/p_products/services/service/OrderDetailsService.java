package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.OrderDetails;

public interface OrderDetailsService {

    OrderDetails save(OrderDetails orderDetails);

    OrderDetailsDTO getOrderDetailDTOById(Long orderDetailId);
    OrderDetails getOrderDetailById(Long orderDetailId);
}
