package com.hunt.otziv.p_products.board;

import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderBoardQueryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderDtoMapper orderDtoMapper;

    @Mock
    private UserService userService;

    @Mock
    private ManagerService managerService;

    @Mock
    private WorkerService workerService;

    @Test
    void getAllOrderDTOAndKeywordUsesSearchRepositoryAndPreservesRequestedIdOrder() {
        OrderBoardQueryService service = service();
        Object[] firstRow = new Object[]{"first"};
        Object[] secondRow = new Object[]{"second"};

        when(orderRepository.findPageIdByKeyWord(eq("needle"), eq("needle"), any(Pageable.class)))
                .thenAnswer(invocation -> page(List.of(2L, 1L), invocation.getArgument(2), 2));
        when(orderRepository.findOrderListRows(List.of(2L, 1L)))
                .thenReturn(List.of(firstRow, secondRow));
        when(orderDtoMapper.toBoardDTO(firstRow)).thenReturn(orderDto(1L));
        when(orderDtoMapper.toBoardDTO(secondRow)).thenReturn(orderDto(2L));

        Page<OrderDTOList> result = service.getAllOrderDTOAndKeyword("needle", 0, 10, "desc");

        assertEquals(List.of(2L, 1L), result.getContent().stream().map(OrderDTOList::getId).toList());
        assertEquals(2, result.getTotalElements());
    }

    @Test
    void getAllOrderDTOAndKeywordUsesAdminRepositoryForBlankKeywordAndKeepsLegacySortDirection() {
        OrderBoardQueryService service = service();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(orderRepository.findPageIdToAdmin(pageableCaptor.capture()))
                .thenAnswer(invocation -> page(List.of(), invocation.getArgument(0), 0));

        Page<OrderDTOList> result = service.getAllOrderDTOAndKeyword("   ", -5, 0, "asc");

        Pageable pageable = pageableCaptor.getValue();
        assertTrue(result.isEmpty());
        assertEquals(0, pageable.getPageNumber());
        assertEquals(1, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("changed").getDirection());
        verify(orderRepository, never()).findPageIdByKeyWord(any(), any(), any());
    }

    @Test
    void managerBoardReturnsEmptyPageWhenPrincipalCannotResolveUser() {
        OrderBoardQueryService service = service();
        Principal principal = () -> "manager";

        when(userService.findByUserName("manager")).thenReturn(Optional.empty());

        Page<OrderDTOList> result = service.getAllOrderDTOAndKeywordByManagerAll(principal, "needle", 0, 10);

        assertTrue(result.isEmpty());
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(managerService);
    }

    @Test
    void ownerBoardUsesOwnerManagersForStatusSearch() {
        OrderBoardQueryService service = service();
        Principal principal = () -> "owner";
        Manager manager = new Manager();
        User user = new User();
        user.setManagers(Set.of(manager));

        when(userService.findByUserName("owner")).thenReturn(Optional.of(user));
        when(orderRepository.findPageIdByOwnerAndKeyWordAndStatus(
                eq(List.of(manager)),
                eq("needle"),
                eq("Новый"),
                eq("needle"),
                eq("Новый"),
                any(Pageable.class)
        )).thenAnswer(invocation -> page(List.of(), invocation.getArgument(5), 0));

        Page<OrderDTOList> result = service.getAllOrderDTOAndKeywordByOwner(
                principal,
                "needle",
                "Новый",
                0,
                10,
                "desc"
        );

        assertTrue(result.isEmpty());
        verify(orderRepository).findPageIdByOwnerAndKeyWordAndStatus(
                eq(List.of(manager)),
                eq("needle"),
                eq("Новый"),
                eq("needle"),
                eq("Новый"),
                any(Pageable.class)
        );
    }

    private OrderBoardQueryService service() {
        return new OrderBoardQueryService(
                orderRepository,
                orderDtoMapper,
                userService,
                managerService,
                workerService
        );
    }

    private Page<Long> page(List<Long> ids, Pageable pageable, long total) {
        return new PageImpl<>(ids, pageable, total);
    }

    private OrderDTOList orderDto(Long id) {
        return OrderDTOList.builder()
                .id(id)
                .build();
    }
}
