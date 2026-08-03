package com.hunt.otziv.manager.controller;

import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.manager.dto.api.OrderEditResponse;
import com.hunt.otziv.manager.dto.api.OrderUpdateRequest;
import com.hunt.otziv.manager.dto.api.StatusChangeRequest;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.manager.services.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.services.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.payment.service.OrderPaymentCancellationService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.WorkerService;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.HashSet;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager")
public class ApiManagerOrderController {

    private final OrderService orderService;
    private final OrderDetailsService orderDetailsService;
    private final ReviewService reviewService;
    private final ManagerBoardEditAssembler managerBoardEditAssembler;
    private final ManagerPermissionService managerPermissionService;
    private final ManagerAccessService managerAccessService;
    private final OrderPaymentCancellationService orderPaymentCancellationService;
    private final CompanyRepository companyRepository;
    private final OrderRepository orderRepository;
    private final WorkerService workerService;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;

    @PostMapping("/orders/{orderId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    @Transactional
    public void updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody StatusChangeRequest request,
            HttpServletRequest servletRequest,
            Authentication authentication
    ) throws Exception {
        managerAccessService.requireOrderAccess(orderId, authentication);
        orderAggregateMutationLockService.lock(orderId);
        managerAccessService.requireOrderAccess(orderId, authentication);
        String status = requireStatus(request);
        servletRequest.setAttribute("status", status);
        if (managerPermissionService.hasOnlyWorkerRole(authentication) && !"В проверку".equals(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Специалист может отправить заказ только на проверку");
        }

        boolean updated = "Бан".equals(status) && managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER")
                ? orderService.changeStatusForPrivilegedOrder(orderId, status)
                : orderService.changeStatusForOrder(orderId, status);

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
        managerAccessService.requireOrderAccess(orderId, authentication);
        return managerBoardEditAssembler.buildOrderEditResponse(orderService.getOrderDTO(orderId), principal, authentication);
    }

    @PutMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    @Transactional
    public OrderEditResponse updateOrder(
            @PathVariable Long orderId,
            @RequestBody OrderUpdateRequest request,
            Principal principal,
            Authentication authentication
    ) {
        managerAccessService.requireOrderAccess(orderId, authentication);
        orderAggregateMutationLockService.lock(orderId);
        managerAccessService.requireOrderAccess(orderId, authentication);
        OrderDTO current = orderService.getOrderDTO(orderId);
        validateOrderUpdateRelations(current, request, authentication);
        requireCompanyAccessForCompanyMutations(current, request, authentication);
        OrderDTO update = toOrderUpdateDto(current, request, orderId, authentication);

        try {
            updateCompanyWorkersForTransfer(current, request, orderId, authentication);
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

    private void updateCompanyWorkersForTransfer(
            OrderDTO current,
            OrderUpdateRequest request,
            Long orderId,
            Authentication authentication
    ) {
        Long previousWorkerId = idOf(current.getWorker());
        Long selectedWorkerId = request.workerId();
        if (selectedWorkerId == null || selectedWorkerId <= 0 || Objects.equals(previousWorkerId, selectedWorkerId)) {
            return;
        }

        Long companyId = current.getCompany() == null ? null : current.getCompany().getId();
        if (companyId == null) {
            throw new IllegalArgumentException("У заказа не указана компания");
        }

        Company company = companyRepository.findByIdWithWorkers(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Компания не найдена"));
        Worker selectedWorker = workerService.getWorkerById(selectedWorkerId);
        if (selectedWorker == null) {
            throw new IllegalArgumentException("Новый специалист не найден");
        }

        if (company.getWorkers() == null) {
            company.setWorkers(new HashSet<>());
        }
        company.getWorkers().add(selectedWorker);

        if (Boolean.TRUE.equals(request.removePreviousWorkerFromCompany()) && previousWorkerId != null) {
            if (orderRepository.existsByCompany_IdAndWorker_IdAndCompleteFalseAndIdNot(companyId, previousWorkerId, orderId)) {
                throw new IllegalArgumentException("У прежнего специалиста есть другие активные заказы этой компании");
            }
            company.getWorkers().removeIf(worker -> Objects.equals(worker.getId(), previousWorkerId));
        }
        companyRepository.save(company);
    }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void deleteOrder(
            @PathVariable Long orderId,
            Principal principal,
            Authentication authentication
    ) {
        managerAccessService.requireOrderAccess(orderId, authentication);
        if (!orderService.deleteOrder(orderId, principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Заказ не удален: недостаточно прав или статус не позволяет удаление");
        }
    }

    @PostMapping("/orders/{orderId}/payment-cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public OrderEditResponse cancelOrderPayment(
            @PathVariable Long orderId,
            Principal principal,
            Authentication authentication
    ) {
        managerAccessService.requireOrderAccess(orderId, authentication);
        orderPaymentCancellationService.cancelPayment(orderId, principal);
        return managerBoardEditAssembler.buildOrderEditResponse(orderService.getOrderDTO(orderId), principal, authentication);
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

        boolean canComplete = managerPermissionService.hasRole(authentication, "ADMIN") || managerPermissionService.hasRole(authentication, "OWNER");

        return OrderDTO.builder()
                .id(orderId)
                .filial(FilialDTO.builder().id(firstId(request.filialId(), idOf(current.getFilial()))).build())
                .worker(WorkerDTO.builder().workerId(firstId(request.workerId(), idOf(current.getWorker()))).build())
                .manager(ManagerDTO.builder().managerId(firstId(request.managerId(), idOf(current.getManager()))).build())
                // Publication progress is derived from persisted reviews, never from the browser.
                .counter(current.getCounter())
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

    private void validateOrderUpdateRelations(
            OrderDTO current,
            OrderUpdateRequest request,
            Authentication authentication
    ) {
        if (current == null || current.getCompany() == null || current.getCompany().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У заказа не указана компания");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные заказа не переданы");
        }

        Long currentFilialId = idOf(current.getFilial());
        Long currentWorkerId = idOf(current.getWorker());
        Long currentManagerId = idOf(current.getManager());
        if (managerPermissionService.hasOnlyWorkerRole(authentication)) {
            if ((request.filialId() != null && !Objects.equals(request.filialId(), currentFilialId))
                    || (request.workerId() != null && !Objects.equals(request.workerId(), currentWorkerId))
                    || (request.managerId() != null && !Objects.equals(request.managerId(), currentManagerId))
                    || (request.counter() != null && !Objects.equals(request.counter(), current.getCounter()))
                    || (request.complete() != null && !Objects.equals(request.complete(), current.isComplete()))
                    || Boolean.TRUE.equals(request.removePreviousWorkerFromCompany())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Специалист может менять только заметки заказа");
            }
            return;
        }

        Long targetManagerId = firstId(request.managerId(), currentManagerId);
        if (!managerAccessService.canAccessManager(targetManagerId, authentication)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Менеджер не найден");
        }

        Long targetFilialId = firstId(request.filialId(), currentFilialId);
        boolean filialBelongsToCompany = current.getCompany().getFilials() != null
                && current.getCompany().getFilials().stream()
                .anyMatch(filial -> Objects.equals(filial.getId(), targetFilialId)
                        && (!filial.isArchived() || Objects.equals(targetFilialId, currentFilialId)));
        if (!filialBelongsToCompany) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Филиал не найден у компании");
        }

        Long targetWorkerId = firstId(request.workerId(), currentWorkerId);
        Long companyManagerId = current.getCompany().getManager() == null
                ? null
                : current.getCompany().getManager().getManagerId();
        boolean workerBelongsToCompanyManager = companyManagerId != null
                && workerService.getAllWorkersByManagerId(companyManagerId).stream()
                .anyMatch(worker -> Objects.equals(worker.getWorkerId(), targetWorkerId));
        if (!workerBelongsToCompanyManager) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не закреплен за менеджером компании");
        }
    }

    private void requireCompanyAccessForCompanyMutations(
            OrderDTO current,
            OrderUpdateRequest request,
            Authentication authentication
    ) {
        Long currentWorkerId = idOf(current.getWorker());
        Long targetWorkerId = firstId(request.workerId(), currentWorkerId);
        boolean workerTransfer = !Objects.equals(targetWorkerId, currentWorkerId);
        boolean workerMembershipMissing = targetWorkerId != null
                && targetWorkerId > 0
                && (current.getCompany().getWorkers() == null
                || current.getCompany().getWorkers().stream()
                .noneMatch(worker -> Objects.equals(worker.getWorkerId(), targetWorkerId)));
        boolean companyCommentsChanged = !Objects.equals(
                normalize(request.commentsCompany()),
                current.getCommentsCompany()
        );

        if (workerTransfer || workerMembershipMissing || companyCommentsChanged) {
            managerAccessService.requireCompanyAccess(current.getCompany().getId(), authentication);
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
