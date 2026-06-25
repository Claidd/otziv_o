package com.hunt.otziv.p_products.board;

import com.hunt.otziv.common.BoardLiveSlice;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderBoardQueryService {

    private final OrderRepository orderRepository;
    private final OrderDtoMapper orderDtoMapper;
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;

    public Page<OrderDTOList> getAllOrderDTOCompanyIdAndKeyword(Long companyId, String keyword, int pageNumber, int pageSize) {
        return getAllOrderDTOCompanyIdAndKeyword(companyId, keyword, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOCompanyIdAndKeyword(Long companyId, String keyword, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByCompanyIdAndKeyWord(companyId, keyword, keyword, pageable);
        } else {
            orderIds = orderRepository.findPageIdByCompanyId(companyId, pageable);
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize) {
        return getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByKeyWordLive(
                    keyword,
                    keyword,
                    BoardLiveSlice.ACTIVE_ORDER_STATUSES,
                    pageable
            );
        } else {
            orderIds = orderRepository.findPageIdToAdminLive(
                    BoardLiveSlice.ACTIVE_ORDER_STATUSES,
                    pageable
            );
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize) {
        return getAllOrderDTOAndKeywordAndStatus(keyword, status, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize, String sortDirection) {
        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByKeyWordAndStatus(keyword, status, keyword, status, pageable);
        } else {
            orderIds = orderRepository.findPageIdByStatus(status, pageable);
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        return getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection) {
        return getAllOrderDTOAndKeywordByManagerAll(
                resolveManagerFromPrincipal(principal),
                keyword,
                pageNumber,
                pageSize,
                sortDirection
        );
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Manager manager, String keyword, int pageNumber, int pageSize, String sortDirection) {
        return getAllOrderDTOAndKeywordByManagerAll(
                manager,
                keyword,
                pageNumber,
                pageSize,
                sortDirection,
                BoardLiveSlice.ACTIVE_ORDER_STATUSES
        );
    }

    public Page<OrderDTOList> getWorkerBoardOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection) {
        return getAllOrderDTOAndKeywordByManagerAll(
                resolveManagerFromPrincipal(principal),
                keyword,
                pageNumber,
                pageSize,
                sortDirection,
                BoardLiveSlice.WORKER_BOARD_ALL_ORDER_STATUSES
        );
    }

    private Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(
            Manager manager,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            Collection<String> liveStatuses
    ) {
        if (manager == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByByManagerAndKeyWordLive(
                    manager,
                    keyword,
                    keyword,
                    liveStatuses,
                    pageable
            );
        } else {
            orderIds = orderRepository.findPageIdToManagerLive(
                    manager,
                    liveStatuses,
                    pageable
            );
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        return getAllOrderDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize, String sortDirection) {
        return getAllOrderDTOAndKeywordByManager(
                resolveManagerFromPrincipal(principal),
                keyword,
                status,
                pageNumber,
                pageSize,
                sortDirection
        );
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Manager manager, String keyword, String status, int pageNumber, int pageSize, String sortDirection) {
        if (manager == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByManagerAndKeyWordAndStatus(manager, keyword, status, keyword, status, pageable);
        } else {
            orderIds = orderRepository.findPageIdByManagerAndStatus(manager, status, pageable);
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getManagerControlOverdueOrdersByManager(
            Manager manager,
            String keyword,
            String status,
            LocalDate cutoff,
            Collection<String> excludedStatuses,
            Collection<CommonInvoiceStatus> commonInvoiceStatuses,
            Collection<String> paymentAutomationStatuses,
            Collection<ClientMessageScenario> paymentScenarios,
            Collection<String> reviewCheckAutomationStatuses,
            Collection<ClientMessageScenario> reviewCheckScenarios,
            Collection<String> deliveryRetryAutomationStatuses,
            Collection<ClientMessageScenario> deliveryRetryScenarios,
            Collection<String> clientTextAutomationStatuses,
            Collection<ClientMessageScenario> clientTextScenarios,
            ScheduledMessageStateStatus activeStatus,
            ScheduledMessageStateStatus doneStatus,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        if (manager == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        String safeKeyword = keyword == null ? "" : keyword.trim();
        Page<Long> orderIds = orderRepository.findPageIdForManagerControlOverdueByManager(
                manager,
                status == null || status.isBlank() ? "Все" : status.trim(),
                safeKeyword,
                safeKeyword,
                cutoff,
                Set.copyOf(excludedStatuses),
                Set.copyOf(commonInvoiceStatuses),
                Set.copyOf(paymentAutomationStatuses),
                Set.copyOf(paymentScenarios),
                Set.copyOf(reviewCheckAutomationStatuses),
                Set.copyOf(reviewCheckScenarios),
                Set.copyOf(deliveryRetryAutomationStatuses),
                Set.copyOf(deliveryRetryScenarios),
                Set.copyOf(clientTextAutomationStatuses),
                Set.copyOf(clientTextScenarios),
                activeStatus,
                doneStatus,
                orderPageable(pageNumber, pageSize, sortDirection)
        );
        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwnerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        return getAllOrderDTOAndKeywordByOwnerAll(principal, keyword, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwnerAll(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection) {
        List<Manager> managerList = resolveOwnerManagersFromPrincipal(principal);
        if (managerList.isEmpty()) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByOwnerAndKeyWordLive(
                    managerList,
                    keyword,
                    keyword,
                    BoardLiveSlice.ACTIVE_ORDER_STATUSES,
                    pageable
            );
        } else {
            orderIds = orderRepository.findPageIdToOwnerLive(
                    managerList,
                    BoardLiveSlice.ACTIVE_ORDER_STATUSES,
                    pageable
            );
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        return getAllOrderDTOAndKeywordByOwner(principal, keyword, status, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize, String sortDirection) {
        List<Manager> managerList = resolveOwnerManagersFromPrincipal(principal);
        if (managerList.isEmpty()) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByOwnerAndKeyWordAndStatus(managerList, keyword, status, keyword, status, pageable);
        } else {
            orderIds = orderRepository.findPageIdByOwnerAndStatus(managerList, status, pageable);
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        Worker worker = resolveWorkerFromPrincipal(principal);
        return getAllOrderDTOAndKeywordByWorkerAll(worker, keyword, pageNumber, pageSize);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(Worker worker, String keyword, int pageNumber, int pageSize) {
        return getAllOrderDTOAndKeywordByWorkerAll(
                worker,
                keyword,
                pageNumber,
                pageSize,
                BoardLiveSlice.ACTIVE_ORDER_STATUSES,
                "desc"
        );
    }

    public Page<OrderDTOList> getWorkerBoardOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        return getWorkerBoardOrderDTOAndKeywordByWorkerAll(principal, keyword, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getWorkerBoardOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection) {
        Worker worker = resolveWorkerFromPrincipal(principal);
        return getWorkerBoardOrderDTOAndKeywordByWorkerAll(worker, keyword, pageNumber, pageSize, sortDirection);
    }

    public Page<OrderDTOList> getWorkerBoardOrderDTOAndKeywordByWorkerAll(Worker worker, String keyword, int pageNumber, int pageSize) {
        return getWorkerBoardOrderDTOAndKeywordByWorkerAll(worker, keyword, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getWorkerBoardOrderDTOAndKeywordByWorkerAll(Worker worker, String keyword, int pageNumber, int pageSize, String sortDirection) {
        return getAllOrderDTOAndKeywordByWorkerAll(
                worker,
                keyword,
                pageNumber,
                pageSize,
                BoardLiveSlice.WORKER_BOARD_ALL_ORDER_STATUSES,
                sortDirection
        );
    }

    private Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(
            Worker worker,
            String keyword,
            int pageNumber,
            int pageSize,
            Collection<String> liveStatuses,
            String sortDirection
    ) {
        if (worker == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        Pageable pageable = workerBoardPageable(pageNumber, pageSize);
        boolean ascending = "asc".equalsIgnoreCase(sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByByWorkerAndKeyWordForBoardLiveSorted(
                    worker,
                    keyword,
                    keyword,
                    liveStatuses,
                    ascending ? "asc" : "desc",
                    pageable
            );
        } else {
            orderIds = orderRepository.findPageIdToWorkerForBoardLiveSorted(
                    worker,
                    liveStatuses,
                    ascending ? "asc" : "desc",
                    pageable
            );
        }

        return getOrderDTOPage(orderIds);
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorker(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        Worker worker = resolveWorkerFromPrincipal(principal);
        return getAllOrderDTOAndKeywordByWorker(worker, keyword, status, pageNumber, pageSize, "desc");
    }

    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorker(
            Worker worker,
            String keyword,
            String status,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        if (worker == null) {
            return emptyOrderPage(pageNumber, pageSize);
        }

        Pageable pageable = orderPageable(pageNumber, pageSize, sortDirection);
        Page<Long> orderIds;

        if (hasText(keyword)) {
            orderIds = orderRepository.findPageIdByWorkerAndKeyWordAndStatus(worker, keyword, status, keyword, status, pageable);
        } else {
            orderIds = orderRepository.findPageIdByWorkerAndStatus(worker, status, pageable);
        }

        return getOrderDTOPage(orderIds);
    }

    private Page<OrderDTOList> getOrderDTOPage(Page<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), orderIds.getPageable(), orderIds.getTotalElements());
        }

        List<Long> ids = orderIds.getContent();
        Map<Long, Integer> orderById = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            orderById.put(ids.get(i), i);
        }

        Map<Long, OrderDTOList> dtoById = new LinkedHashMap<>();
        for (Object[] row : orderRepository.findOrderListRows(ids)) {
            OrderDTOList dto = orderDtoMapper.toBoardDTO(row);
            Long orderId = dto != null ? dto.getId() : null;
            if (orderId != null && !dtoById.containsKey(orderId)) {
                dtoById.put(orderId, dto);
            }
        }

        List<OrderDTOList> orderListDTOs = dtoById.values().stream()
                .sorted(Comparator.comparingInt(order -> orderById.getOrDefault(order.getId(), Integer.MAX_VALUE)))
                .collect(Collectors.toList());

        return new PageImpl<>(orderListDTOs, orderIds.getPageable(), orderIds.getTotalElements());
    }

    private Pageable orderPageable(int pageNumber, int pageSize, String sortDirection) {
        Sort changedSort = "asc".equalsIgnoreCase(sortDirection)
                ? Sort.by("changed").descending()
                : Sort.by("changed").ascending();
        Sort idSort = "asc".equalsIgnoreCase(sortDirection)
                ? Sort.by("id").descending()
                : Sort.by("id").ascending();
        return PageRequest.of(Math.max(pageNumber, 0), Math.max(pageSize, 1), changedSort.and(idSort));
    }

    private Pageable workerBoardPageable(int pageNumber, int pageSize) {
        return PageRequest.of(Math.max(pageNumber, 0), Math.max(pageSize, 1));
    }

    private Page<OrderDTOList> emptyOrderPage(int pageNumber, int pageSize) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        return new PageImpl<>(Collections.emptyList(), pageable, 0);
    }

    private User resolveUserFromPrincipal(Principal principal) {
        if (principal == null) {
            return null;
        }
        return userService.findByUserName(principal.getName()).orElse(null);
    }

    private Manager resolveManagerFromPrincipal(Principal principal) {
        User user = resolveUserFromPrincipal(principal);
        if (user == null) {
            return null;
        }
        return managerService.getManagerByUserId(user.getId());
    }

    private Worker resolveWorkerFromPrincipal(Principal principal) {
        User user = resolveUserFromPrincipal(principal);
        if (user == null) {
            return null;
        }
        return workerService.getWorkerByUserId(user.getId());
    }

    private List<Manager> resolveOwnerManagersFromPrincipal(Principal principal) {
        if (principal == null) {
            return Collections.emptyList();
        }
        return userService.findManagersByUserName(principal.getName()).stream().toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

}
