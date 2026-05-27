package com.hunt.otziv.p_products.next_order;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class NextOrderAutomationService {

    private final NextOrderRequestRepository requestRepository;
    private final NextOrderRequestService requestService;
    private final OrderCreationService creationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createNextOrder(Long requestId) {
        NextOrderRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Next order request not found: " + requestId));

        if (request.getStatus() == NextOrderRequestStatus.CREATED) {
            log.info("Заявка {} уже закрыта созданным заказом", requestId);
            return;
        }

        Order sourceOrder = request.getSourceOrder();
        if (sourceOrder == null) {
            throw new IllegalStateException("У заявки " + requestId + " нет исходного заказа");
        }

        Long companyId = sourceOrder.getCompany() != null ? sourceOrder.getCompany().getId() : null;
        Long filialId = sourceOrder.getFilial() != null ? sourceOrder.getFilial().getId() : null;
        Set<Long> filialIds = requestService.orderFilialIds(sourceOrder);
        List<Order> existingActiveOrders = requestService.findActiveOrdersForFilials(companyId, filialIds, filialId);
        if (!existingActiveOrders.isEmpty()) {
            Order activeOrder = existingActiveOrders.getFirst();
            request.setCreatedOrder(activeOrder);
            request.setErrorMessage(null);
            requestRepository.save(request);
            requestService.markCreatedIfOpen(requestId);
            log.info(
                    "Заявка {} закрыта: для компании {}, филиала {} уже есть активный заказ {}",
                    requestId,
                    companyId,
                    filialId,
                    activeOrder.getId()
            );
            return;
        }

        requestService.markAttemptStarted(requestId);

        Long productId = productId(sourceOrder);
        OrderDTO repeatOrder = creationService.convertToOrderDTOToRepeat(sourceOrder);
        boolean created = creationService.createNewOrderWithReviews(companyId, productId, repeatOrder);
        if (!created) {
            throw new IllegalStateException("createNewOrderWithReviews вернул false для заявки " + requestId);
        }

        requestService.markCreatedIfOpen(requestId);
    }

    private Long productId(Order sourceOrder) {
        if (sourceOrder.getDetails() == null || sourceOrder.getDetails().isEmpty()) {
            throw new IllegalStateException("У исходного заказа " + sourceOrder.getId() + " нет деталей");
        }

        OrderDetails details = sourceOrder.getDetails().getFirst();
        if (details == null || details.getProduct() == null || details.getProduct().getId() == null) {
            throw new IllegalStateException("У исходного заказа " + sourceOrder.getId() + " не найден продукт");
        }

        return details.getProduct().getId();
    }
}
