package com.hunt.otziv.manager.controller;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.manager.dto.api.OrderUpdateRequest;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.manager.service.ManagerBoardEditAssembler;
import com.hunt.otziv.manager.service.ManagerPermissionService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.payment.service.OrderPaymentCancellationService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.service.WorkerService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiManagerOrderControllerAccessTest {

    @Mock private OrderService orderService;
    @Mock private OrderDetailsService orderDetailsService;
    @Mock private ReviewService reviewService;
    @Mock private ManagerBoardEditAssembler managerBoardEditAssembler;
    @Mock private ManagerPermissionService managerPermissionService;
    @Mock private ManagerAccessService managerAccessService;
    @Mock private OrderPaymentCancellationService orderPaymentCancellationService;
    @Mock private CompanyRepository companyRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private WorkerService workerService;
    @Mock private OrderAggregateMutationLockService orderAggregateMutationLockService;

    @InjectMocks
    private ApiManagerOrderController controller;

    @Test
    void companyCommentMutationIsRejectedWhenOrderAndCompanyScopesDiverge() {
        Authentication actor = managerAuthentication();
        OrderDTO current = order(10L, 20L, 30L, "Чужая заметка");
        OrderUpdateRequest request = request(null, "Измененная заметка");
        stubRelations(current, actor, 30L);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"))
                .when(managerAccessService).requireCompanyAccess(100L, actor);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.updateOrder(50L, request, actor, actor)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(orderService, never()).updateOrder(any(), any(), any());
        verify(companyRepository, never()).findByIdWithWorkers(any());
    }

    @Test
    void companyCommentMutationSucceedsWhenCompanyScopeAllowsIt() {
        Authentication actor = managerAuthentication();
        OrderDTO current = order(10L, 10L, 30L, "Старая заметка");
        OrderUpdateRequest request = request(null, "Новая заметка");
        stubRelations(current, actor, 30L);

        controller.updateOrder(50L, request, actor, actor);

        verify(managerAccessService).requireCompanyAccess(100L, actor);
        verify(orderService).updateOrder(any(OrderDTO.class), org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(50L));
    }

    @Test
    void workerTransferRequiresCompanyScopeBeforeMembershipMutation() {
        Authentication actor = managerAuthentication();
        OrderDTO current = order(10L, 20L, 30L, "Заметка");
        OrderUpdateRequest request = request(31L, "Заметка");
        stubRelations(current, actor, 31L);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"))
                .when(managerAccessService).requireCompanyAccess(100L, actor);

        assertThrows(
                ResponseStatusException.class,
                () -> controller.updateOrder(50L, request, actor, actor)
        );

        verify(managerAccessService).requireCompanyAccess(100L, actor);
        verify(companyRepository, never()).findByIdWithWorkers(any());
        verify(orderService, never()).updateOrder(any(), any(), any());
    }

    private void stubRelations(OrderDTO current, Authentication actor, Long targetWorkerId) {
        when(orderService.getOrderDTO(50L)).thenReturn(current);
        when(managerAccessService.canAccessManager(10L, actor)).thenReturn(true);
        when(workerService.getAllWorkersByManagerId(current.getCompany().getManager().getManagerId()))
                .thenReturn(Set.of(WorkerDTO.builder().workerId(targetWorkerId).build()));
    }

    private OrderDTO order(Long orderManagerId, Long companyManagerId, Long workerId, String companyComments) {
        FilialDTO filial = FilialDTO.builder().id(40L).title("Филиал").build();
        CompanyDTO company = CompanyDTO.builder()
                .id(100L)
                .manager(ManagerDTO.builder().managerId(companyManagerId).build())
                .workers(Set.of(WorkerDTO.builder().workerId(workerId).build()))
                .filials(Set.of(filial))
                .commentsCompany(companyComments)
                .build();
        return OrderDTO.builder()
                .id(50L)
                .company(company)
                .manager(ManagerDTO.builder().managerId(orderManagerId).build())
                .worker(WorkerDTO.builder().workerId(workerId).build())
                .filial(filial)
                .commentsCompany(companyComments)
                .orderComments("Заметка заказа")
                .counter(0)
                .build();
    }

    private OrderUpdateRequest request(Long workerId, String companyComments) {
        return new OrderUpdateRequest(
                null,
                workerId,
                null,
                null,
                "Заметка заказа",
                companyComments,
                null,
                false
        );
    }

    private Authentication managerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                "manager",
                "password",
                Set.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );
    }
}
