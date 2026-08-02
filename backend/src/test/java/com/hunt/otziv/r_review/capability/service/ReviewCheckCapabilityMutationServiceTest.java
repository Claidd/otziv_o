package com.hunt.otziv.r_review.capability.service;

import com.hunt.otziv.r_review.capability.model.ReviewCheckCapabilityScope;
import com.hunt.otziv.r_review.capability.repository.ReviewCheckCapabilityRepository;
import com.hunt.otziv.r_review.capability.repository.ReviewCheckCapabilityResourceRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.capability.repository.ReviewCheckCapabilityResourceRepository.ArchivedResourceBinding;
import com.hunt.otziv.r_review.capability.repository.ReviewCheckCapabilityRepository.CapabilityRow;
import com.hunt.otziv.r_review.capability.service.ReviewCheckCapabilityService.IssuedCapability;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class ReviewCheckCapabilityMutationServiceTest {

    private ReviewCheckCapabilityService capabilityService;
    private ReviewCheckCapabilityRepository capabilityRepository;
    private OrderRepository orderRepository;
    private OrderDetailsRepository orderDetailsRepository;
    private ReviewCheckCapabilityResourceRepository resourceRepository;
    private ManagerAccessService managerAccessService;
    private UserRepository userRepository;
    private ReviewCheckCapabilityMutationService service;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        capabilityService = mock(ReviewCheckCapabilityService.class);
        capabilityRepository = mock(ReviewCheckCapabilityRepository.class);
        orderRepository = mock(OrderRepository.class);
        orderDetailsRepository = mock(OrderDetailsRepository.class);
        resourceRepository = mock(ReviewCheckCapabilityResourceRepository.class);
        managerAccessService = mock(ManagerAccessService.class);
        userRepository = mock(UserRepository.class);
        service = new ReviewCheckCapabilityMutationService(
                capabilityService,
                capabilityRepository,
                orderRepository,
                orderDetailsRepository,
                resourceRepository,
                managerAccessService,
                userRepository
        );
        authentication = new UsernamePasswordAuthenticationToken("manager", "unused");
    }

    @Test
    void issueLocksCanonicalOrderThenChecksBindingAndAuthorizationBeforeMutation() {
        UUID orderDetailId = UUID.randomUUID();
        IssuedCapability issued = issued(orderDetailId, 91L);
        allowLiveResource(11L, orderDetailId);
        when(capabilityService.issue(orderDetailId, Set.of("VIEW"), 30, 77L)).thenReturn(issued);

        assertThat(service.issue(11L, orderDetailId, Set.of("VIEW"), 30, authentication))
                .isSameAs(issued);

        InOrder ordered = inOrder(
                orderRepository,
                orderDetailsRepository,
                managerAccessService,
                userRepository,
                capabilityService
        );
        ordered.verify(orderRepository).findByIdForCounterUpdate(11L);
        ordered.verify(orderDetailsRepository).existsByIdAndOrder_Id(orderDetailId, 11L);
        ordered.verify(managerAccessService).requireOrderAccess(11L, authentication);
        ordered.verify(userRepository).findByUsername("manager");
        ordered.verify(capabilityService).issue(orderDetailId, Set.of("VIEW"), 30, 77L);
        verifyNoInteractions(resourceRepository);
    }

    @Test
    void rotateUsesCapabilityThenOrderLockOrderBeforeAuthorizationAndMutation() {
        UUID orderDetailId = UUID.randomUUID();
        IssuedCapability issued = issued(orderDetailId, 92L);
        allowCapability(55L, orderDetailId);
        allowLiveResource(12L, orderDetailId);
        when(capabilityService.rotate(55L, orderDetailId, 14, 77L)).thenReturn(issued);

        assertThat(service.rotate(12L, 55L, orderDetailId, 14, authentication)).isSameAs(issued);

        InOrder ordered = inOrder(
                capabilityRepository,
                orderRepository,
                orderDetailsRepository,
                managerAccessService,
                userRepository,
                capabilityService
        );
        ordered.verify(capabilityRepository).findByIdForUpdate(55L);
        ordered.verify(orderRepository).findByIdForCounterUpdate(12L);
        ordered.verify(orderDetailsRepository).existsByIdAndOrder_Id(orderDetailId, 12L);
        ordered.verify(managerAccessService).requireOrderAccess(12L, authentication);
        ordered.verify(userRepository).findByUsername("manager");
        ordered.verify(capabilityService).rotate(55L, orderDetailId, 14, 77L);
    }

    @Test
    void revokeUsesCapabilityThenOrderLockOrderBeforeAuthorizationAndMutation() {
        UUID orderDetailId = UUID.randomUUID();
        allowCapability(56L, orderDetailId);
        allowLiveResource(13L, orderDetailId);

        service.revoke(13L, 56L, orderDetailId, "operator", authentication);

        InOrder ordered = inOrder(
                capabilityRepository,
                orderRepository,
                orderDetailsRepository,
                managerAccessService,
                userRepository,
                capabilityService
        );
        ordered.verify(capabilityRepository).findByIdForUpdate(56L);
        ordered.verify(orderRepository).findByIdForCounterUpdate(13L);
        ordered.verify(orderDetailsRepository).existsByIdAndOrder_Id(orderDetailId, 13L);
        ordered.verify(managerAccessService).requireOrderAccess(13L, authentication);
        ordered.verify(userRepository).findByUsername("manager");
        ordered.verify(capabilityService).revoke(56L, orderDetailId, 77L, "operator");
    }

    @Test
    void issueAuthorizationDenialHasNoCapabilityOrActorSideEffects() {
        UUID orderDetailId = UUID.randomUUID();
        denyLiveResource(21L, orderDetailId);

        assertThatThrownBy(() -> service.issue(
                21L,
                orderDetailId,
                Set.of("VIEW"),
                30,
                authentication
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(this::assertNotFound);

        verifyNoInteractions(userRepository, capabilityService, resourceRepository);
    }

    @Test
    void rotateAuthorizationDenialHasNoCapabilityOrActorSideEffects() {
        UUID orderDetailId = UUID.randomUUID();
        allowCapability(81L, orderDetailId);
        denyLiveResource(22L, orderDetailId);

        assertThatThrownBy(() -> service.rotate(22L, 81L, orderDetailId, 30, authentication))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(this::assertNotFound);

        verifyNoInteractions(userRepository, capabilityService, resourceRepository);
    }

    @Test
    void revokeAuthorizationDenialHasNoCapabilityOrActorSideEffects() {
        UUID orderDetailId = UUID.randomUUID();
        allowCapability(82L, orderDetailId);
        denyLiveResource(23L, orderDetailId);

        assertThatThrownBy(() -> service.revoke(23L, 82L, orderDetailId, "operator", authentication))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(this::assertNotFound);

        verifyNoInteractions(userRepository, capabilityService, resourceRepository);
    }

    @Test
    void rotateRejectsCapabilityFromAnotherDetailBeforeTakingOrderLock() {
        UUID requestedDetailId = UUID.randomUUID();
        allowCapability(84L, UUID.randomUUID());

        assertThatThrownBy(() -> service.rotate(24L, 84L, requestedDetailId, 30, authentication))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(this::assertNotFound);

        verifyNoInteractions(orderRepository, orderDetailsRepository, managerAccessService,
                resourceRepository, userRepository, capabilityService);
    }

    @Test
    void crossOrderDetailDenialStopsBeforeAuthorizationAndMutation() {
        UUID orderDetailId = UUID.randomUUID();
        when(orderRepository.findByIdForCounterUpdate(31L))
                .thenReturn(Optional.of(Order.builder().id(31L).build()));
        when(orderDetailsRepository.existsByIdAndOrder_Id(orderDetailId, 31L)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(
                31L,
                orderDetailId,
                Set.of("VIEW"),
                30,
                authentication
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(this::assertNotFound);

        verify(managerAccessService, never()).requireOrderAccess(anyLong(), any(Authentication.class));
        verifyNoInteractions(userRepository, capabilityService, resourceRepository);
    }

    @Test
    void archivedMutationUsesLockedSnapshotAssignmentAfterCompanyReassignment() {
        UUID orderDetailId = UUID.randomUUID();
        IssuedCapability issued = issued(orderDetailId, 93L);
        when(orderRepository.findByIdForCounterUpdate(41L)).thenReturn(Optional.empty());
        when(resourceRepository.findArchivedByOrderDetailIdForUpdate(orderDetailId))
                .thenReturn(Optional.of(new ArchivedResourceBinding(41L, 301L, 401L)));
        when(managerAccessService.canAccessArchivedOrder(301L, 401L, authentication)).thenReturn(true);
        when(userRepository.findByUsername("manager"))
                .thenReturn(Optional.of(User.builder().id(77L).username("manager").build()));
        when(capabilityService.issue(orderDetailId, Set.of("VIEW"), 30, 77L)).thenReturn(issued);

        assertThat(service.issue(41L, orderDetailId, Set.of("VIEW"), 30, authentication))
                .isSameAs(issued);

        InOrder ordered = inOrder(
                orderRepository,
                resourceRepository,
                managerAccessService,
                userRepository,
                capabilityService
        );
        ordered.verify(orderRepository).findByIdForCounterUpdate(41L);
        ordered.verify(resourceRepository).findArchivedByOrderDetailIdForUpdate(orderDetailId);
        ordered.verify(managerAccessService).canAccessArchivedOrder(301L, 401L, authentication);
        ordered.verify(userRepository).findByUsername("manager");
        ordered.verify(capabilityService).issue(orderDetailId, Set.of("VIEW"), 30, 77L);
        verify(managerAccessService, never()).requireCompanyAccess(anyLong(), any(Authentication.class));
    }

    @Test
    void archivedSnapshotDenialAfterCompanyReassignmentHasNoMutationSideEffects() {
        UUID orderDetailId = UUID.randomUUID();
        allowCapability(83L, orderDetailId);
        when(orderRepository.findByIdForCounterUpdate(42L)).thenReturn(Optional.empty());
        when(resourceRepository.findArchivedByOrderDetailIdForUpdate(orderDetailId))
                .thenReturn(Optional.of(new ArchivedResourceBinding(42L, 302L, 402L)));
        when(managerAccessService.canAccessArchivedOrder(302L, 402L, authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.rotate(42L, 83L, orderDetailId, 30, authentication))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(this::assertNotFound);

        verify(managerAccessService, never()).requireCompanyAccess(anyLong(), any(Authentication.class));
        verifyNoInteractions(userRepository, capabilityService);
    }

    @Test
    void mutationBoundaryAndCanonicalOrderLockAreExplicitContracts() throws Exception {
        for (Method method : new Method[]{
                ReviewCheckCapabilityMutationService.class.getMethod(
                        "issue", Long.class, UUID.class, Set.class, Integer.class, Authentication.class
                ),
                ReviewCheckCapabilityMutationService.class.getMethod(
                        "rotate", Long.class, long.class, UUID.class, Integer.class, Authentication.class
                ),
                ReviewCheckCapabilityMutationService.class.getMethod(
                        "revoke", Long.class, long.class, UUID.class, String.class, Authentication.class
                )
        }) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).as(method.getName()).isNotNull();
            assertThat(transactional.readOnly()).as(method.getName()).isFalse();
        }

        Lock lock = OrderRepository.class
                .getMethod("findByIdForCounterUpdate", Long.class)
                .getAnnotation(Lock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private void allowLiveResource(Long orderId, UUID orderDetailId) {
        when(orderRepository.findByIdForCounterUpdate(orderId))
                .thenReturn(Optional.of(Order.builder().id(orderId).build()));
        when(orderDetailsRepository.existsByIdAndOrder_Id(orderDetailId, orderId)).thenReturn(true);
        when(userRepository.findByUsername("manager"))
                .thenReturn(Optional.of(User.builder().id(77L).username("manager").build()));
    }

    private void allowCapability(Long capabilityId, UUID orderDetailId) {
        when(capabilityRepository.findByIdForUpdate(capabilityId))
                .thenReturn(Optional.of(new CapabilityRow(
                        capabilityId,
                        orderDetailId,
                        new byte[]{1},
                        "OPAQUE",
                        ReviewCheckCapabilityScope.ALL_PUBLIC_MASK,
                        77L,
                        LocalDateTime.now().plusDays(30),
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now()
                )));
    }

    private void denyLiveResource(Long orderId, UUID orderDetailId) {
        when(orderRepository.findByIdForCounterUpdate(orderId))
                .thenReturn(Optional.of(Order.builder().id(orderId).build()));
        when(orderDetailsRepository.existsByIdAndOrder_Id(orderDetailId, orderId)).thenReturn(true);
        doThrow(notFound()).when(managerAccessService).requireOrderAccess(orderId, authentication);
    }

    private IssuedCapability issued(UUID orderDetailId, long id) {
        return new IssuedCapability(
                id,
                orderDetailId,
                "raw-once",
                Set.of("VIEW"),
                LocalDateTime.now().plusDays(30)
        );
    }

    private void assertNotFound(Throwable throwable) {
        assertThat(((ResponseStatusException) throwable).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден");
    }
}
