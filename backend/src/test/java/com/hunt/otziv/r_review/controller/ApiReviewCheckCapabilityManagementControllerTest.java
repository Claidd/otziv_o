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
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityMutationService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService.IssuedCapability;
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
    private ReviewCheckCapabilityMutationService mutationService;
    private OrderDetailsService orderDetailsService;
    private ReviewCheckArchiveService archiveService;
    private ManagerAccessService managerAccessService;
    private ApiReviewCheckCapabilityManagementController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        capabilityService = mock(ReviewCheckCapabilityService.class);
        mutationService = mock(ReviewCheckCapabilityMutationService.class);
        orderDetailsService = mock(OrderDetailsService.class);
        archiveService = mock(ReviewCheckArchiveService.class);
        managerAccessService = mock(ManagerAccessService.class);
        controller = new ApiReviewCheckCapabilityManagementController(
                capabilityService,
                mutationService,
                orderDetailsService,
                archiveService,
                managerAccessService
        );
        authentication = new UsernamePasswordAuthenticationToken("manager", "unused");
    }

    @Test
    void issueDelegatesAtomicAuthorizationAndMutationToService() {
        UUID orderDetailId = UUID.randomUUID();
        IssuedCapability issued = new IssuedCapability(
                91L,
                orderDetailId,
                "raw-once",
                Set.of("VIEW"),
                LocalDateTime.now().plusDays(1)
        );
        when(mutationService.issue(11L, orderDetailId, Set.of("VIEW"), 1, authentication))
                .thenReturn(issued);

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
        verify(mutationService).issue(11L, orderDetailId, Set.of("VIEW"), 1, authentication);
    }

    @Test
    void crossOrderDetailIsIndistinguishableFromMissingResource() {
        UUID orderDetailId = UUID.randomUUID();
        when(mutationService.issue(11L, orderDetailId, Set.of("VIEW"), 1, authentication))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена"));

        assertThatThrownBy(() -> controller.issue(
                11L,
                new ApiReviewCheckCapabilityManagementController.IssueRequest(orderDetailId, Set.of("VIEW"), 1),
                authentication
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(mutationService).issue(11L, orderDetailId, Set.of("VIEW"), 1, authentication);
        verify(capabilityService, never()).issue(orderDetailId, Set.of("VIEW"), 1, 77L);
    }

    @Test
    void archivedCapabilityKeepsSnapshotAssignmentAccessAfterCompanyReassignment() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId))
                .thenThrow(new UsernameNotFoundException("archived"));
        when(archiveService.findByOrderDetailId(orderDetailId)).thenReturn(Optional.of(new ArchivedReviewCheck(
                orderDetailId,
                11L,
                22L,
                33L,
                44L,
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
        when(managerAccessService.canAccessArchivedOrder(33L, 44L, authentication)).thenReturn(true);

        controller.list(11L, orderDetailId, authentication);

        verify(managerAccessService).canAccessArchivedOrder(33L, 44L, authentication);
        verify(managerAccessService, never()).requireCompanyAccess(22L, authentication);
        verify(capabilityService).list(orderDetailId);
    }
}
