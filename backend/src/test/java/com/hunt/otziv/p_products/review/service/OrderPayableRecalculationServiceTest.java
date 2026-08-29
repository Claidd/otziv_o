package com.hunt.otziv.p_products.review.service;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.review.event.OrderPayableChangedEvent;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.r_review.model.Review;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderPayableRecalculationServiceTest {

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    void recalculatesEveryDetailAndWholeOrderFromReviews() {
        Product firstProduct = product(1L);
        Product changedProduct = product(2L);
        Order order = new Order();
        order.setId(42L);
        OrderDetails first = detail(order, firstProduct, List.of(
                review(changedProduct, "200.00"),
                review(changedProduct, "250.00")
        ));
        OrderDetails second = detail(order, firstProduct, List.of(review(firstProduct, "100.00")));
        order.setDetails(List.of(first, second));

        new OrderPayableRecalculationService(orderDetailsService, eventPublisher).recalculate(first);

        assertEquals(2, first.getAmount());
        assertEquals(new BigDecimal("450.00"), first.getPrice());
        assertSame(changedProduct, first.getProduct());
        assertEquals(1, second.getAmount());
        assertEquals(new BigDecimal("100.00"), second.getPrice());
        assertEquals(3, order.getAmount());
        assertEquals(new BigDecimal("550.00"), order.getSum());
        verify(orderDetailsService).save(first);
        verify(orderDetailsService).save(second);
        verify(orderDetailsService).saveOrder(order);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(new OrderPayableChangedEvent(42L), event.getValue());
    }

    @Test
    void keepsSummaryProductWhenDetailContainsMixedProducts() {
        Product summaryProduct = product(1L);
        Product secondProduct = product(2L);
        Order order = new Order();
        order.setId(43L);
        OrderDetails detail = detail(order, summaryProduct, List.of(
                review(summaryProduct, "200.00"),
                review(secondProduct, "250.00")
        ));
        order.setDetails(List.of(detail));

        new OrderPayableRecalculationService(orderDetailsService, eventPublisher).recalculate(detail);

        assertSame(summaryProduct, detail.getProduct());
        assertEquals(2, detail.getAmount());
        assertEquals(new BigDecimal("450.00"), detail.getPrice());
        assertEquals(new BigDecimal("450.00"), order.getSum());
    }

    private OrderDetails detail(Order order, Product product, List<Review> reviews) {
        OrderDetails detail = new OrderDetails();
        detail.setOrder(order);
        detail.setProduct(product);
        detail.setReviews(reviews);
        reviews.forEach(review -> review.setOrderDetails(detail));
        return detail;
    }

    private Review review(Product product, String price) {
        Review review = new Review();
        review.setProduct(product);
        review.setPrice(new BigDecimal(price));
        return review;
    }

    private Product product(Long id) {
        Product product = new Product();
        product.setId(id);
        return product;
    }
}
