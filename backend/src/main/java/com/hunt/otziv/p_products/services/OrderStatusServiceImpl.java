package com.hunt.otziv.p_products.services;

import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderStatusRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderStatusServiceImpl implements OrderStatusService {

    private final OrderStatusRepository orderStatusRepository;

    public OrderStatus getOrderStatusByTitle(String title){ // Взять статус заказа по названию
        return orderStatusRepository.findByTitle(title).orElse(null);
    } // Взять статус заказа по названию


    public OrderStatusDTO getOrderStatusDTOByTitle(String title) { // Взять статус заказа дто по названию
        OrderStatus orderStatus = orderStatusRepository.findByTitle(title)
                .orElseThrow(() -> new NotFoundException("Order status not found for title: " + title));
        return convertOrderStatusToDTO(orderStatus);
    } // Взять статус заказа дто по названию

    private OrderStatusDTO convertOrderStatusToDTO(OrderStatus orderStatus) { // Перевод статуса заказа в дто
        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    } // Перевод статуса заказа в дто

}
