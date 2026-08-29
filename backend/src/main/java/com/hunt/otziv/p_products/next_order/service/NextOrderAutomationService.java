package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.service.OrderCreationService;
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
        if (request.getStatus() == NextOrderRequestStatus.CANCELED) {
            log.info("Заявка {} отменена, следующий заказ не создаем", requestId);
            return;
        }

        Order sourceOrder = request.getSourceOrder();
        if (sourceOrder == null) {
            throw new IllegalStateException("У заявки " + requestId + " нет исходного заказа");
        }

        Long companyId = sourceOrder.getCompany() != null ? sourceOrder.getCompany().getId() : null;
        Long filialId = sourceOrder.getFilial() != null ? sourceOrder.getFilial().getId() : null;
        Long workerId = sourceOrder.getWorker() != null ? sourceOrder.getWorker().getId() : null;
        Set<Long> filialIds = requestService.orderFilialIds(sourceOrder);
        List<Order> existingActiveOrders = requestService.findActiveOrdersForFilials(companyId, filialIds, filialId, workerId);
        if (!existingActiveOrders.isEmpty()) {
            Order activeOrder = existingActiveOrders.getFirst();
            request.setCreatedOrder(activeOrder);
            request.setErrorMessage(null);
            requestRepository.save(request);
            requestService.markCreatedIfOpen(requestId);
            log.info(
                    "Заявка {} закрыта: для компании {}, филиала {} и исполнителя {} уже есть активный заказ {}",
                    requestId,
                    companyId,
                    filialId,
                    workerId,
                    activeOrder.getId()
            );
            return;
        }

        requestService.markAttemptStarted(requestId);

        OrderDTO repeatOrder = creationService.convertToOrderDTOToRepeat(sourceOrder);
        boolean created = creationService.createRepeatedOrderWithReviews(sourceOrder, repeatOrder);
        if (!created) {
            throw new IllegalStateException("createRepeatedOrderWithReviews вернул false для заявки " + requestId);
        }

        requestService.markCreatedIfOpen(requestId);
    }
}
