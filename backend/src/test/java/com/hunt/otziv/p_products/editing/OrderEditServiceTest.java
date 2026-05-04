package com.hunt.otziv.p_products.editing;

import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
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

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(filialService.getFilial(2L)).thenReturn(newFilial);

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

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(workerService.getWorkerById(2L)).thenReturn(newWorker);

        service.updateOrder(dto, 100L, 10L);

        assertSame(newWorker, order.getWorker());
        assertSame(newWorker, review.getWorker());
        verify(orderRepository).save(order);
        verifyNoInteractions(reviewService);
    }

    @Test
    void updateOrderToWorkerUpdatesOnlyEditableComments() {
        OrderEditService service = service();
        Company company = new Company();
        company.setCommentsCompany("old company comment");
        Order order = new Order();
        order.setId(10L);
        order.setZametka("old order comment");
        order.setCompany(company);
        OrderDTO dto = OrderDTO.builder()
                .orderComments("new order comment")
                .commentsCompany("new company comment")
                .build();

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        service.updateOrderToWorker(dto, 100L, 10L);

        assertEquals("new order comment", order.getZametka());
        assertEquals("new company comment", company.getCommentsCompany());
        verify(orderRepository).save(order);
    }

    @Test
    void updateOrderToWorkerSkipsSaveWhenNothingChanged() {
        OrderEditService service = service();
        Company company = new Company();
        company.setCommentsCompany("same company comment");
        Order order = new Order();
        order.setId(10L);
        order.setZametka("same order comment");
        order.setCompany(company);
        OrderDTO dto = OrderDTO.builder()
                .orderComments("same order comment")
                .commentsCompany("same company comment")
                .build();

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        service.updateOrderToWorker(dto, 100L, 10L);

        verify(orderRepository, never()).save(order);
    }

    private OrderEditService service() {
        return new OrderEditService(
                orderRepository,
                workerService,
                managerService,
                filialService,
                reviewService
        );
    }

    private Order orderWithDetails(Filial filial, Worker worker, List<Review> reviews) {
        Order order = new Order();
        order.setId(10L);
        order.setFilial(filial);
        order.setWorker(worker);

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
