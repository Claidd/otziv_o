package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.api.DisputedOrderArchive;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DisputedOrderArchiveService implements DisputedOrderArchive {
    private final OrderRepository orderRepository;
    private final OrderStatusTransitionService statusTransitionService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean hasPaymentState(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        return order == null || order.isComplete() || order.getPayDay() != null;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean archive(Long orderId) throws Exception {
        return statusTransitionService.changeStatusForOrder(orderId, "Архив");
    }
}
