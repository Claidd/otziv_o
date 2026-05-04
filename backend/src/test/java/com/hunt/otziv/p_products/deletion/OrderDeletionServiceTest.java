package com.hunt.otziv.p_products.deletion;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDeletionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private ReviewService reviewService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminDeletesReviewsDetailsAndOrderInOrder() {
        OrderDeletionService service = service();
        Order order = order(10L, "Архив");
        OrderDetails firstDetail = detail(List.of(review(1L), review(null), review(2L)));
        OrderDetails secondDetail = detail(null);
        Principal principal = () -> "admin";

        authenticateWithRole("ROLE_ADMIN");
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(10L)).thenReturn(List.of(firstDetail, secondDetail));

        boolean result = service.deleteOrder(10L, principal);

        assertTrue(result);
        InOrder inOrder = inOrder(reviewService, orderDetailsService, orderRepository);
        inOrder.verify(reviewService).deleteAllByIdIn(List.of(1L, 2L));
        inOrder.verify(orderDetailsService).deleteAllByOrderId(10L);
        inOrder.verify(orderRepository).delete(order);
    }

    @Test
    void managerCannotDeleteNonNewOrder() {
        OrderDeletionService service = service();
        Order order = order(11L, "Публикация");

        authenticateWithRole("ROLE_MANAGER");
        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));

        boolean result = service.deleteOrder(11L, () -> "manager");

        assertFalse(result);
        verify(orderDetailsService, never()).findByOrderId(11L);
        verifyNoInteractions(reviewService);
        verify(orderRepository, never()).delete(order);
    }

    @Test
    void managerCanDeleteNewOrderWhenRoleComesFromUserDetailsPrincipal() {
        OrderDeletionService service = service();
        Order order = order(12L, "Новый");
        User userDetails = new User(
                "manager",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, "password")
        );
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order));
        when(orderDetailsService.findByOrderId(12L)).thenReturn(List.of());

        boolean result = service.deleteOrder(12L, () -> "manager");

        assertTrue(result);
        verify(orderDetailsService).deleteAllByOrderId(12L);
        verify(orderRepository).delete(order);
        verifyNoInteractions(reviewService);
    }

    private OrderDeletionService service() {
        return new OrderDeletionService(
                orderRepository,
                orderDetailsService,
                reviewService,
                new OrderDeletionPolicy()
        );
    }

    private void authenticateWithRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "user",
                        "password",
                        List.of(new SimpleGrantedAuthority(role))
                )
        );
    }

    private Order order(Long id, String statusTitle) {
        OrderStatus status = new OrderStatus();
        status.setTitle(statusTitle);

        Order order = new Order();
        order.setId(id);
        order.setStatus(status);
        return order;
    }

    private OrderDetails detail(List<Review> reviews) {
        OrderDetails detail = new OrderDetails();
        detail.setId(UUID.randomUUID());
        detail.setReviews(reviews);
        return detail;
    }

    private Review review(Long id) {
        Review review = new Review();
        review.setId(id);
        return review;
    }
}
