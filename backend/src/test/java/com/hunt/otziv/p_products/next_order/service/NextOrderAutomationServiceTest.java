package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.service.OrderCreationService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NextOrderAutomationServiceTest {

    @Mock
    private NextOrderRequestRepository requestRepository;

    @Mock
    private NextOrderRequestService requestService;

    @Mock
    private OrderCreationService creationService;

    @Test
    void reusesActiveOrderWhenReviewTermsMatchAsMultisetRegardlessOfReviewIds() {
        Order sourceOrder = order(
                30L,
                review(102L, 2L, "250"),
                review(101L, 1L, "200")
        );
        Order activeOrder = order(
                40L,
                review(202L, 1L, "200.0", 20L),
                review(201L, 2L, "250.00")
        );
        Order newerMismatchingOrder = order(
                41L,
                review(302L, 1L, "200"),
                review(301L, 1L, "200")
        );
        NextOrderRequest request = request(50L, sourceOrder);
        stubRequestAndActiveOrders(request, newerMismatchingOrder, activeOrder);

        service().createNextOrder(50L);

        assertSame(activeOrder, request.getCreatedOrder());
        assertNull(request.getErrorMessage());
        verify(requestRepository).save(request);
        verify(requestService).markCreatedIfOpen(50L);
        verify(requestService, never()).markAttemptStarted(50L);
        verifyNoInteractions(creationService);
    }

    @Test
    void createsRepeatedOrderWhenActiveOrderHasDifferentReviewProduct() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 1L, "250"));

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenActiveOrderHasDifferentSnapshotPrice() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 2L, "200"));

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenActiveOrderHasDifferentEffectiveFilial() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 2L, "250", 21L));

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenOrderFilialDiffersEvenWithMatchingExplicitReviewFilials() {
        Order sourceOrder = order(30L, review(101L, 2L, "250", 20L));
        Order activeOrder = order(40L, review(201L, 2L, "250", 20L));
        activeOrder.setFilial(null);

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenOrderWorkerDiffersIncludingNull() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 2L, "250"));
        activeOrder.setWorker(null);

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenActiveOrderAmountDoesNotMatchItsCards() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 2L, "250"));
        activeOrder.setAmount(2);

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenActiveOrderTotalDoesNotMatchItsCards() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 2L, "250"));
        activeOrder.setSum(new BigDecimal("200"));

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenActiveDetailAmountDoesNotMatchItsCards() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 2L, "250"));
        activeOrder.getDetails().getFirst().setAmount(2);

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    @Test
    void createsRepeatedOrderWhenActiveDetailTotalDoesNotMatchItsCards() {
        Order sourceOrder = order(30L, review(101L, 2L, "250"));
        Order activeOrder = order(40L, review(201L, 2L, "250"));
        activeOrder.getDetails().getFirst().setPrice(new BigDecimal("200"));

        verifyMismatchCreatesRepeatedOrder(sourceOrder, activeOrder);
    }

    private void verifyMismatchCreatesRepeatedOrder(Order sourceOrder, Order activeOrder) {
        NextOrderRequest request = request(50L, sourceOrder);
        OrderDTO repeatOrder = new OrderDTO();
        stubRequestAndActiveOrders(request, activeOrder);
        when(creationService.convertToOrderDTOToRepeat(sourceOrder)).thenReturn(repeatOrder);
        when(creationService.createRepeatedOrderWithReviews(sourceOrder, repeatOrder)).thenReturn(true);

        service().createNextOrder(50L);

        assertNull(request.getCreatedOrder());
        verify(requestRepository, never()).save(any(NextOrderRequest.class));
        verify(requestService).markAttemptStarted(50L);
        verify(creationService).convertToOrderDTOToRepeat(sourceOrder);
        verify(creationService).createRepeatedOrderWithReviews(sourceOrder, repeatOrder);
        verify(requestService).markCreatedIfOpen(50L);
    }

    private void stubRequestAndActiveOrders(NextOrderRequest request, Order... activeOrders) {
        Order sourceOrder = request.getSourceOrder();
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(requestService.orderFilialIds(sourceOrder)).thenReturn(Set.of(20L));
        when(requestService.findActiveOrdersForFilials(10L, Set.of(20L), 20L, 70L))
                .thenReturn(List.of(activeOrders));
    }

    private NextOrderAutomationService service() {
        return new NextOrderAutomationService(requestRepository, requestService, creationService);
    }

    private NextOrderRequest request(Long id, Order sourceOrder) {
        NextOrderRequest request = NextOrderRequest.builder()
                .company(sourceOrder.getCompany())
                .filial(sourceOrder.getFilial())
                .sourceOrder(sourceOrder)
                .status(NextOrderRequestStatus.PENDING)
                .build();
        request.setId(id);
        return request;
    }

    private Order order(Long id, Review... reviews) {
        Company company = new Company();
        company.setId(10L);
        Filial filial = new Filial();
        filial.setId(20L);
        Worker worker = new Worker();
        worker.setId(70L);
        BigDecimal total = Arrays.stream(reviews)
                .map(Review::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderDetails details = OrderDetails.builder()
                .reviews(Arrays.asList(reviews))
                .amount(reviews.length)
                .price(total)
                .build();
        Order order = Order.builder()
                .id(id)
                .amount(reviews.length)
                .sum(total)
                .company(company)
                .filial(filial)
                .worker(worker)
                .details(List.of(details))
                .build();
        details.setOrder(order);
        Arrays.stream(reviews).forEach(review -> review.setOrderDetails(details));
        return order;
    }

    private Review review(Long id, Long productId, String price) {
        return review(id, productId, price, null);
    }

    private Review review(Long id, Long productId, String price, Long filialId) {
        Filial reviewFilial = null;
        if (filialId != null) {
            reviewFilial = new Filial();
            reviewFilial.setId(filialId);
        }
        return Review.builder()
                .id(id)
                .filial(reviewFilial)
                .product(Product.builder().id(productId).build())
                .price(new BigDecimal(price))
                .build();
    }
}
