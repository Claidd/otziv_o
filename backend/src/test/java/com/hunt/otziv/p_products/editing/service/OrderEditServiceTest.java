package com.hunt.otziv.p_products.editing.service;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEditServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private WorkerService workerService;

    @Mock
    private ManagerService managerService;

    @Mock
    private FilialService filialService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Mock
    private ReviewRecoveryTaskService reviewRecoveryTaskService;

    @Mock
    private OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Mock
    private ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;

    @Test
    void updateOrderChangesFilialOnOrderAndReviews() {
        OrderEditService service = service();
        Filial currentFilial = filial(1L);
        Filial newFilial = filial(2L);
        Review review = new Review();
        Order order = orderWithDetails(currentFilial, null, List.of(review));
        OrderDTO dto = OrderDTO.builder()
                .filial(FilialDTO.builder().id(2L).build())
                .build();

        newFilial.setCompany(order.getCompany());
        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);
        when(filialService.getFilial(2L)).thenReturn(newFilial);
        when(reviewRepository.getAllByOrderId(10L)).thenReturn(List.of(review));

        service.updateOrder(dto, 100L, 10L);

        assertSame(newFilial, order.getFilial());
        assertSame(newFilial, review.getFilial());
        verify(reviewService).save(review);
        verify(orderRepository).save(order);
    }

    @Test
    void updateOrderChangesWorkerOnOrderAndNestedReviews() {
        OrderEditService service = service();
        Worker oldWorker = worker(1L);
        Worker newWorker = worker(2L);
        Review review = new Review();
        review.setWorker(oldWorker);
        Order order = orderWithDetails(null, oldWorker, List.of(review));
        OrderDTO dto = OrderDTO.builder()
                .worker(WorkerDTO.builder().workerId(2L).build())
                .build();

        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);
        when(workerService.getWorkerById(2L)).thenReturn(newWorker);
        when(workerService.getAllWorkersByManagerId(50L))
                .thenReturn(Set.of(WorkerDTO.builder().workerId(2L).build()));

        service.updateOrder(dto, 100L, 10L);

        assertSame(newWorker, order.getWorker());
        verify(reviewRepository).reassignWorkerByOrderId(10L, newWorker);
        verify(badReviewTaskService).reassignPendingTasksForOrder(10L, newWorker);
        verify(reviewRecoveryTaskService).reassignPendingTasksForOrder(10L, newWorker);
        assertEquals(true, order.getCompany().getWorkers().contains(newWorker));
        verify(orderRepository).save(order);
        verifyNoInteractions(reviewService);
    }

    @Test
    void updateOrderCannotChangeManagerAfterRecipientRouteWasFrozen() {
        OrderEditService service = service();
        Order order = orderWithDetails(null, null, List.of());
        OrderDTO dto = OrderDTO.builder()
                .manager(ManagerDTO.builder().managerId(51L).build())
                .build();
        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "получатель уже зафиксирован"
        )).when(contractorRouteAssignmentGuard).requireManagerReassignmentAllowed(10L);

        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.updateOrder(dto, 100L, 10L)
        );

        verify(contractorRouteAssignmentGuard).requireManagerReassignmentAllowed(10L);
        verify(managerService, never()).getManagerById(51L);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void updateOrderDoesNotHideFailureWhileMovingAnOrderBundle() {
        OrderEditService service = service();
        Worker oldWorker = worker(1L);
        Worker newWorker = worker(2L);
        Order order = orderWithDetails(null, oldWorker, List.of());
        OrderDTO dto = OrderDTO.builder()
                .worker(WorkerDTO.builder().workerId(2L).build())
                .build();

        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);
        when(workerService.getWorkerById(2L)).thenReturn(newWorker);
        when(workerService.getAllWorkersByManagerId(50L))
                .thenReturn(Set.of(WorkerDTO.builder().workerId(2L).build()));
        when(reviewRecoveryTaskService.reassignPendingTasksForOrder(10L, newWorker))
                .thenThrow(new IllegalStateException("recovery update failed"));

        assertThrows(
                IllegalStateException.class,
                () -> service.updateOrder(dto, 100L, 10L)
        );
        verify(orderRepository, never()).save(order);
    }

    @Test
    void updateOrderToWorkerUpdatesOnlyEditableComments() {
        OrderEditService service = service();
        Company company = new Company();
        company.setId(100L);
        company.setCommentsCompany("old company comment");
        Order order = new Order();
        order.setId(10L);
        order.setZametka("old order comment");
        order.setCompany(company);
        OrderDTO dto = OrderDTO.builder()
                .orderComments("new order comment")
                .commentsCompany("new company comment")
                .build();

        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);

        service.updateOrderToWorker(dto, 100L, 10L);

        assertEquals("new order comment", order.getZametka());
        assertEquals("new company comment", company.getCommentsCompany());
        verify(orderRepository).save(order);
    }

    @Test
    void updateOrderToWorkerSkipsSaveWhenNothingChanged() {
        OrderEditService service = service();
        Company company = new Company();
        company.setId(100L);
        company.setCommentsCompany("same company comment");
        Order order = new Order();
        order.setId(10L);
        order.setZametka("same order comment");
        order.setCompany(company);
        OrderDTO dto = OrderDTO.builder()
                .orderComments("same order comment")
                .commentsCompany("same company comment")
                .build();

        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);

        service.updateOrderToWorker(dto, 100L, 10L);

        verify(orderRepository, never()).save(order);
    }

    @Test
    void updateOrderIgnoresBrowserCounterAndRepairsItFromPublishedReviews() {
        OrderEditService service = service();
        Order order = orderWithDetails(null, null, List.of());
        order.setCounter(1);
        OrderDTO dto = OrderDTO.builder().counter(999).build();

        when(orderAggregateMutationLockService.lock(10L)).thenReturn(order);
        when(reviewRepository.countPublishedByOrderId(10L)).thenReturn(2);

        service.updateOrder(dto, 100L, 10L);

        assertEquals(2, order.getCounter());
        verify(orderRepository).save(order);
    }

    private OrderEditService service() {
        return new OrderEditService(
                orderRepository,
                workerService,
                managerService,
                filialService,
                reviewService,
                reviewRepository,
                badReviewTaskService,
                reviewRecoveryTaskService,
                orderAggregateMutationLockService,
                contractorRouteAssignmentGuard
        );
    }

    private Order orderWithDetails(Filial filial, Worker worker, List<Review> reviews) {
        Order order = new Order();
        order.setId(10L);
        order.setFilial(filial);
        order.setWorker(worker);
        Company company = new Company();
        company.setId(100L);
        company.setManager(Manager.builder().id(50L).build());
        order.setCompany(company);
        if (filial != null) {
            filial.setCompany(company);
        }

        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        detail.setReviews(reviews);
        order.setDetails(List.of(detail));
        return order;
    }

    private Filial filial(Long id) {
        Filial filial = new Filial();
        filial.setId(id);
        return filial;
    }

    private Worker worker(Long id) {
        Worker worker = new Worker();
        worker.setId(id);
        return worker;
    }
}
