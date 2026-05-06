package com.hunt.otziv.manager.controller;

import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.manager.dto.api.OrderEditResponse;
import com.hunt.otziv.manager.dto.api.OrderUpdateRequest;
import com.hunt.otziv.manager.dto.api.StatusChangeRequest;
import com.hunt.otziv.manager.services.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager")
public class ApiManagerOrderController {

    private final OrderService orderService;
    private final OrderDetailsService orderDetailsService;
    private final ReviewService reviewService;
    private final ManagerBoardEditAssembler managerBoardEditAssembler;
    private final ManagerPermissionService managerPermissionService;

    @PostMapping("/orders/{orderId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public void updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody StatusChangeRequest request,
            Authentication authentication
    ) throws Exception {
        String status = requireStatus(request);
        if (managerPermissionService.hasOnlyWorkerRole(authentication) && !"В проверку".equals(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Специалист может отправить заказ только на проверку");
        }

        Order order = orderService.getOrder(orderId);

        if ("Опубликовано".equals(status) || "Оплачено".equals(status)) {
            requireCompleteCounter(order, status);
        }

        boolean updated = orderService.changeStatusForOrder(orderId, status);

        if (!updated) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус заказа не изменен");
        }

        if ("Публикация".equals(status)) {
            updateReviewPublishDates(orderId);
        }
    }

    @GetMapping("/orders/{orderId}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderEditResponse getOrderEdit(
            @PathVariable Long orderId,
            Principal principal,
            Authentication authentication
    ) {
        return managerBoardEditAssembler.buildOrderEditResponse(orderService.getOrderDTO(orderId), principal, authentication);
    }

    @PutMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderEditResponse updateOrder(
            @PathVariable Long orderId,
            @RequestBody OrderUpdateRequest request,
            Principal principal,
            Authentication authentication
    ) {
        OrderDTO current = orderService.getOrderDTO(orderId);
        OrderDTO update = toOrderUpdateDto(current, request, orderId, authentication);

        try {
            if (managerPermissionService.hasOnlyWorkerRole(authentication)) {
                orderService.updateOrderToWorker(update, current.getCompany().getId(), orderId);
            } else {
                orderService.updateOrder(update, current.getCompany().getId(), orderId);
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ не сохранен: " + exception.getMessage(), exception);
        }

        return managerBoardEditAssembler.buildOrderEditResponse(orderService.getOrderDTO(orderId), principal, authentication);
    }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void deleteOrder(
            @PathVariable Long orderId,
            Principal principal
    ) {
        if (!orderService.deleteOrder(orderId, principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Заказ не удален: недостаточно прав или статус не позволяет удаление");
        }
    }

    private OrderDTO toOrderUpdateDto(
            OrderDTO current,
            OrderUpdateRequest request,
            Long orderId,
            Authentication authentication
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные заказа не переданы");
        }

        if (request.counter() != null && request.counter() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Счетчик не может быть меньше нуля");
        }

        boolean canComplete = managerPermissionService.hasRole(authentication, "ADMIN") || managerPermissionService.hasRole(authentication, "OWNER");

        return OrderDTO.builder()
                .id(orderId)
                .filial(FilialDTO.builder().id(firstId(request.filialId(), idOf(current.getFilial()))).build())
                .worker(WorkerDTO.builder().workerId(firstId(request.workerId(), idOf(current.getWorker()))).build())
                .manager(ManagerDTO.builder().managerId(firstId(request.managerId(), idOf(current.getManager()))).build())
                .counter(request.counter() != null ? request.counter() : current.getCounter())
                .orderComments(normalize(request.orderComments()))
                .commentsCompany(normalize(request.commentsCompany()))
                .complete(canComplete ? Boolean.TRUE.equals(request.complete()) : current.isComplete())
                .build();
    }

    private Long idOf(ManagerDTO manager) {
        return manager == null ? null : manager.getManagerId();
    }

    private Long idOf(WorkerDTO worker) {
        return worker == null ? null : worker.getWorkerId();
    }

    private Long idOf(FilialDTO filial) {
        return filial == null ? null : filial.getId();
    }

    private Long firstId(Long value, Long fallback) {
        return value != null ? value : fallback != null ? fallback : 0L;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String requireStatus(StatusChangeRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус не указан");
        }
        return request.status().trim();
    }

    private void requireCompleteCounter(Order order, String status) {
        if (order.getAmount() > order.getCounter()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя перевести заказ в статус \"" + status + "\": опубликовано "
                            + order.getCounter() + " из " + order.getAmount() + " отзывов"
            );
        }
    }

    private void updateReviewPublishDates(Long orderId) {
        Order order = orderService.getOrder(orderId);
        if (order.getDetails() == null || order.getDetails().isEmpty()) {
            return;
        }

        OrderDetailsDTO orderDetails = orderDetailsService.getOrderDetailDTOById(order.getDetails().getFirst().getId());
        reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetails);
    }
}
