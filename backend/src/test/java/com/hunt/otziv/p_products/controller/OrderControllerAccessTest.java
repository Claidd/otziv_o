package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.services.AmountService;
import com.hunt.otziv.r_review.dto.AmountDTO;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderControllerAccessTest {

    @Mock private ProductService productService;
    @Mock private OrderService orderService;
    @Mock private ReviewService reviewService;
    @Mock private AmountService amountService;
    @Mock private PromoTextService promoTextService;
    @Mock private OrderDetailsService orderDetailsService;
    @Mock private OrderCreationService creationService;
    @Mock private ManagerAccessService managerAccessService;
    @Mock private OrderAggregateMutationLockService orderAggregateMutationLockService;
    @Mock private RedirectAttributes redirectAttributes;
    @Mock private Model model;

    @InjectMocks
    private OrderController controller;

    @Test
    void editViewRejectsForeignOrderBeforeLoadingItsData() {
        Authentication actor = managerAuthentication();
        doThrow(notFound("Заказ не найден"))
                .when(managerAccessService).requireOrderAccess(50L, actor);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.OrderEdit(100L, 50L, model, actor)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(orderService, never()).getOrderDTO(any());
    }

    @Test
    void editPostRejectsForeignOrderBeforeLockAndMutation() {
        Authentication actor = managerAuthentication();
        doThrow(notFound("Заказ не найден"))
                .when(managerAccessService).requireOrderAccess(50L, actor);

        assertThrows(
                ResponseStatusException.class,
                () -> controller.OrderEditPost(
                        order("Новая заметка компании"),
                        100L,
                        50L,
                        redirectAttributes,
                        actor,
                        model,
                        actor
                )
        );

        verify(orderAggregateMutationLockService, never()).lock(any());
        verify(orderService, never()).updateOrder(any(), any(), any());
        verify(orderService, never()).updateOrderToWorker(any(), any(), any());
    }

    @Test
    void companyMutationRequiresCompanyScope() {
        Authentication actor = managerAuthentication();
        OrderDTO current = order("Старая заметка компании");
        OrderDTO requested = order("Новая заметка компании");
        when(orderService.getOrderDTO(50L)).thenReturn(current);
        doThrow(notFound("Компания не найдена"))
                .when(managerAccessService).requireCompanyAccess(100L, actor);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.OrderEditPost(
                        requested,
                        100L,
                        50L,
                        redirectAttributes,
                        actor,
                        model,
                        actor
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(managerAccessService).requireCompanyAccess(100L, actor);
        verify(orderService, never()).updateOrder(any(), any(), any());
    }

    @Test
    void workerCanStillUpdateOrderOnlyCommentWithoutCompanyScope() {
        Authentication actor = workerAuthentication();
        OrderDTO current = order("Заметка компании");
        OrderDTO requested = order("Подмененная заметка компании");
        requested.setOrderComments("Новая заметка заказа");
        when(orderService.getOrderDTO(50L)).thenReturn(current);

        String view = controller.OrderEditPost(
                requested,
                100L,
                50L,
                redirectAttributes,
                actor,
                model,
                actor
        );

        assertEquals("redirect:/ordersCompany/ordersDetails/{companyId}/{orderId}", view);
        assertEquals("Заметка компании", requested.getCommentsCompany());
        verify(managerAccessService, never()).requireCompanyAccess(any(), any());
        verify(orderService).updateOrderToWorker(requested, 100L, 50L);
        verify(orderService, never()).updateOrder(any(), any(), any());
    }

    @Test
    void assignmentToManagerOutsideActorScopeIsRejected() {
        Authentication actor = managerAuthentication();
        OrderDTO current = order("Заметка компании");
        OrderDTO requested = order("Заметка компании");
        requested.setManager(ManagerDTO.builder().managerId(99L).build());
        when(orderService.getOrderDTO(50L)).thenReturn(current);
        when(managerAccessService.canAccessManager(99L, actor)).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.OrderEditPost(
                        requested,
                        100L,
                        50L,
                        redirectAttributes,
                        actor,
                        model,
                        actor
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(orderService, never()).updateOrder(any(), any(), any());
    }

    @Test
    void mismatchedCompanyPathIsRejectedBeforeMutation() {
        Authentication actor = managerAuthentication();
        when(orderService.getOrderDTO(50L)).thenReturn(order("Заметка компании"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.OrderEditPost(
                        order("Заметка компании"),
                        999L,
                        50L,
                        redirectAttributes,
                        actor,
                        model,
                        actor
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(orderService, never()).updateOrder(any(), any(), any());
    }

    @Test
    void companyOrderListRequiresCompanyScopeBeforeQuery() {
        Authentication actor = managerAuthentication();
        doThrow(notFound("Компания не найдена"))
                .when(managerAccessService).requireCompanyAccess(100L, actor);

        assertThrows(
                ResponseStatusException.class,
                () -> controller.OrderListToCompany(100L, "", model, 0, actor)
        );

        verify(orderService, never()).getAllOrderDTOCompanyIdAndKeyword(any(), any(), anyInt(), anyInt());
    }

    @Test
    void newOrderRequiresCompanyScopeBeforeCreation() {
        Authentication actor = managerAuthentication();
        doThrow(notFound("Компания не найдена"))
                .when(managerAccessService).requireCompanyAccess(100L, actor);

        assertThrows(
                ResponseStatusException.class,
                () -> controller.newOrder(
                        order("Заметка компании"),
                        100L,
                        redirectAttributes,
                        3L,
                        model,
                        actor
                )
        );

        verify(creationService, never()).createNewOrderWithReviews(any(), any(), any());
    }

    @Test
    void statusMutationRejectsForeignOrderBeforeLock() {
        Authentication actor = managerAuthentication();
        doThrow(notFound("Заказ не найден"))
                .when(managerAccessService).requireOrderAccess(50L, actor);

        assertThrows(
                ResponseStatusException.class,
                () -> controller.changeStatusOnChecking(50L, 100L, "В проверку", 0, actor)
        );

        verify(orderAggregateMutationLockService, never()).lock(any());
        try {
            verify(orderService, never()).changeStatusForOrder(any(), any());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void statusMutationRejectsMismatchedCompanyAfterLock() {
        Authentication actor = managerAuthentication();
        when(orderAggregateMutationLockService.lock(50L)).thenReturn(lockedOrder(999L));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.changeStatusOnChecking(50L, 100L, "В проверку", 0, actor)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(managerAccessService, times(2)).requireOrderAccess(50L, actor);
        try {
            verify(orderService, never()).changeStatusForOrder(any(), any());
        } catch (Exception verificationFailure) {
            throw new AssertionError(verificationFailure);
        }
    }

    @Test
    void statusMutationLocksAndRechecksAccessBeforeChangingStatus() throws Exception {
        Authentication actor = managerAuthentication();
        when(orderAggregateMutationLockService.lock(50L)).thenReturn(lockedOrder(100L));
        when(orderService.changeStatusForOrder(50L, "На проверке")).thenReturn(true);

        controller.changeStatusOnChecking(50L, 100L, "В проверку", 0, actor);

        verify(managerAccessService, times(2)).requireOrderAccess(50L, actor);
        verify(orderAggregateMutationLockService).lock(50L);
        verify(orderService).changeStatusForOrder(50L, "На проверке");
    }

    @Test
    void managerEditCannotClearCompleteFlagByOmittingPrivilegedCheckbox() {
        Authentication actor = managerAuthentication();
        OrderDTO current = order("Заметка компании");
        current.setComplete(true);
        OrderDTO requested = order("Заметка компании");
        requested.setComplete(false);
        when(orderService.getOrderDTO(50L)).thenReturn(current);

        controller.OrderEditPost(
                requested,
                100L,
                50L,
                redirectAttributes,
                actor,
                model,
                actor
        );

        assertEquals(true, requested.isComplete());
        verify(orderService).updateOrder(requested, 100L, 50L);
    }

    @Test
    void workerCannotInvokeFinancialStatusMutation() {
        Authentication actor = workerAuthentication();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.changeStatusPay(50L, 100L, "Выставлен счет", 0, actor)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(managerAccessService, never()).requireOrderAccess(any(), any());
        verify(orderAggregateMutationLockService, never()).lock(any());
    }

    @Test
    void workerCannotDeleteOwnOrderThroughLegacyRoute() {
        Authentication actor = workerAuthentication();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.OrderEditPostDelete(
                        order("Заметка компании"),
                        100L,
                        50L,
                        redirectAttributes,
                        actor,
                        model,
                        actor
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(orderService, never()).deleteOrder(any(), any());
    }

    @Test
    void workerCanSubmitOwnOrderForChecking() throws Exception {
        Authentication actor = workerAuthentication();
        when(orderAggregateMutationLockService.lock(50L)).thenReturn(lockedOrder(100L));
        when(orderService.changeStatusForOrder(50L, "В проверку")).thenReturn(true);

        controller.changeStatusForChecking(50L, 100L, model, redirectAttributes, actor);

        verify(managerAccessService, times(2)).requireOrderAccess(50L, actor);
        verify(orderService).changeStatusForOrder(50L, "В проверку");
    }

    @Test
    void newOrderRejectsUnknownProductBeforeCreation() {
        Authentication actor = managerAuthentication();
        when(productService.findById(3L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.newOrder(
                        newOrderRequest(10),
                        100L,
                        redirectAttributes,
                        3L,
                        model,
                        actor
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(creationService, never()).createNewOrderWithReviews(any(), any(), any());
    }

    @Test
    void newOrderRejectsAmountOutsideCanonicalServerList() {
        Authentication actor = managerAuthentication();
        OrderDTO request = newOrderRequest(999);
        stubCanonicalNewOrder(actor, request, 10);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.newOrder(request, 100L, redirectAttributes, 3L, model, actor)
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(creationService, never()).createNewOrderWithReviews(any(), any(), any());
    }

    @Test
    void newOrderCanonicalizesForgedWorkflowFieldsBeforeCreation() {
        Authentication actor = managerAuthentication();
        OrderDTO request = newOrderRequest(10);
        request.setCounter(77);
        request.setComplete(true);
        request.setWaitingForClient(true);
        request.setClientTextExpected(true);
        request.setReviewFilialIds(java.util.List.of(999L));
        stubCanonicalNewOrder(actor, request, 10);

        controller.newOrder(request, 100L, redirectAttributes, 3L, model, actor);

        ArgumentCaptor<OrderDTO> captor = ArgumentCaptor.forClass(OrderDTO.class);
        verify(creationService).createNewOrderWithReviews(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(3L),
                captor.capture()
        );
        OrderDTO canonicalized = captor.getValue();
        assertEquals(0, canonicalized.getCounter());
        assertEquals(false, canonicalized.isComplete());
        assertEquals(false, canonicalized.isWaitingForClient());
        assertEquals(false, canonicalized.isClientTextExpected());
        assertEquals(java.util.List.of(), canonicalized.getReviewFilialIds());
    }

    private OrderDTO order(String companyComments) {
        WorkerDTO worker = WorkerDTO.builder().workerId(30L).build();
        CompanyDTO company = CompanyDTO.builder()
                .id(100L)
                .commentsCompany(companyComments)
                .workers(Set.of(worker))
                .build();
        return OrderDTO.builder()
                .id(50L)
                .company(company)
                .manager(ManagerDTO.builder().managerId(10L).build())
                .worker(worker)
                .commentsCompany(companyComments)
                .orderComments("Старая заметка заказа")
                .build();
    }

    private Order lockedOrder(Long companyId) {
        return Order.builder()
                .id(50L)
                .company(Company.builder().id(companyId).build())
                .build();
    }

    private OrderDTO newOrderRequest(int amount) {
        WorkerDTO worker = WorkerDTO.builder().workerId(30L).build();
        FilialDTO filial = FilialDTO.builder().id(40L).archived(false).build();
        ManagerDTO manager = ManagerDTO.builder().managerId(10L).build();
        CompanyDTO company = CompanyDTO.builder()
                .id(100L)
                .manager(manager)
                .workers(Set.of(worker))
                .filials(Set.of(filial))
                .build();
        return OrderDTO.builder()
                .amount(amount)
                .company(company)
                .manager(manager)
                .worker(worker)
                .filial(filial)
                .status(OrderStatusDTO.builder().title("Новый").build())
                .build();
    }

    private void stubCanonicalNewOrder(Authentication actor, OrderDTO request, int allowedAmount) {
        when(productService.findById(3L)).thenReturn(org.mockito.Mockito.mock(Product.class));
        when(orderService.newOrderDTO(100L)).thenReturn(newOrderRequest(allowedAmount));
        when(managerAccessService.canAccessManager(10L, actor)).thenReturn(true);
        when(amountService.getAmountDTOList()).thenReturn(List.of(AmountDTO.builder().amount(allowedAmount).build()));
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private Authentication managerAuthentication() {
        return authentication("manager", "ROLE_MANAGER");
    }

    private Authentication workerAuthentication() {
        return authentication("worker", "ROLE_WORKER");
    }

    private Authentication authentication(String username, String role) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "password",
                Set.of(new SimpleGrantedAuthority(role))
        );
    }
}
