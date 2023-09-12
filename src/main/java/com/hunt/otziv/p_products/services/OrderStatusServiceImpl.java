package com.hunt.otziv.p_products.services;

import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderStatusRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.webjars.NotFoundException;

import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderStatusServiceImpl implements OrderStatusService {

    private final OrderStatusRepository orderStatusRepository;

    public OrderStatus getOrderStatusByTitle(String title){
        return orderStatusRepository.findByTitle(title).orElse(null);
    }


    public OrderStatusDTO getOrderStatusDTOByTitle(String title) {
        OrderStatus orderStatus = orderStatusRepository.findByTitle(title)
                .orElseThrow(() -> new NotFoundException("Order status not found for title: " + title));

        return convertOrderStatusToDTO(orderStatus);
    }

    private OrderStatusDTO convertOrderStatusToDTO(OrderStatus orderStatus) {
        return OrderStatusDTO.builder()
                .id(orderStatus.getId())
                .title(orderStatus.getTitle())
                .build();
    }


//    public OrderStatusDTO getOrderStatusDTOByTitle(String title){
//        System.out.println(title);
//        return convertOrderStatusToDTO(Objects.requireNonNull(orderStatusRepository.findByTitle(title).orElse(null)));
//    }
//
//    private OrderStatusDTO convertOrderStatusToDTO(OrderStatus orderStatus){
//        System.out.println(orderStatus);
//        return OrderStatusDTO.builder()
//                .id(orderStatus.getId())
//                .title(orderStatus.getTitle())
//                .build();
//    }
}
