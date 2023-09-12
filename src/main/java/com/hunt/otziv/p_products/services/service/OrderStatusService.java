package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.OrderStatus;

public interface OrderStatusService {

    OrderStatusDTO getOrderStatusDTOByTitle(String title); // найти статус по названию с переводом в дто

    OrderStatus getOrderStatusByTitle(String title); // найти статус по названию
}
