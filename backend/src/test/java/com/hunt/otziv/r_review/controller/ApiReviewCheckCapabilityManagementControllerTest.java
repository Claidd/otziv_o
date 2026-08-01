package com.hunt.otziv.r_review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.archive.service.ReviewCheckArchiveService;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheck;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService.IssuedCapability;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.server.ResponseStatusException;

class ApiReviewCheckCapabilityManagementControllerTest {

    private ReviewCheckCapabilityService capabilityService;
    private OrderDetailsService orderDetailsService;
    private ReviewCheckArchiveService archiveService;
    private ManagerAccessService managerAccessService;
    private UserRepository userRepository;
    private ApiReviewCheckCapabilityManagementController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        capabilityService = mock(ReviewCheckCapabilityService.class);
        orderDetailsService = mock(OrderDetailsService.class);
        archiveService = mock(ReviewCheckArchiveService.class);
        managerAccessService = mock(ManagerAccessService.class);
        userRepository = mock(UserRepository.class);
        controller = new ApiReviewCheckCapabilityManagementController(
                capabilityService,
                orderDetailsService,
                archiveService,
                managerAccessService,
                userRepository
        );
        authentication = new UsernamePasswordAuthenticationToken("manager", "unused");
        when(userRepository.findByUsername("manager"))
                .thenReturn(Optional.of(User.builder().id(77L).username("manager").build()));
    }

    @Test
    void issueRequiresExactLiveOrderBindingAndManagerObjectAccess() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(OrderDetails.builder()
                        .id(orderDetailId)
                        .order(Order.builder().id(11L).build())
                        .build());
        IssuedCapability issued = new IssuedCapability(
                91L,
                orderDetailId,
                "raw-once",
                Set.of("VIEW"),
                LocalDateTime.now().plusDays(1)
        );
        when(capabilityService.issue(orderDetailId, Set.of("VIEW"), 1, 77L)).thenReturn(issued);

        var response = controller.issue(
                11L,
                new ApiReviewCheckCapabilityManagementController.IssueRequest(
                        orderDetailId,
                        Set.of("VIEW"),
                        1
                ),
                authentication
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isSameAs(issued);
        verify(managerAccessService).requireOrderAccess(11L, authentication);
        verify(capabilityService).issue(orderDetailId, Set.of("VIEW"), 1, 77L);
    }

    @Test
    void crossOrderDetailIsIndistinguishableFromMissingResource() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenReturn(OrderDetails.builder()
                        .id(orderDetailId)
                        .order(Order.builder().id(12L).build())
                        .build());

        assertThatThrownBy(() -> controller.issue(
                11L,
                new ApiReviewCheckCapabilityManagementController.IssueRequest(orderDetailId, Set.of("VIEW"), 1),
                authentication
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(managerAccessService, never()).requireOrderAccess(12L, authentication);
        verify(capabilityService, never()).issue(orderDetailId, Set.of("VIEW"), 1, 77L);
    }

    @Test
    void archivedCapabilityKeepsBindingAndUsesCompanyObjectAccess() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("archived"));
        when(archiveService.findByOrderDetailId(orderDetailId)).thenReturn(Optional.of(new ArchivedReviewCheck(
                orderDetailId,
                11L,
                22L,
                "Компания",
                "Филиал",
                "Архив",
                "",
                "",
                "",
                "",
                1,
                0,
                BigDecimal.TEN,
                false,
                List.of()
        )));

        controller.list(11L, orderDetailId, authentication);

        verify(managerAccessService).requireCompanyAccess(22L, authentication);
        verify(capabilityService).list(orderDetailId);
    }
}
