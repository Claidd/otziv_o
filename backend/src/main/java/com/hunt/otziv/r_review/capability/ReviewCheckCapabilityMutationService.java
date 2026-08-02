package com.hunt.otziv.r_review.capability;

import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityResourceRepository.ArchivedResourceBinding;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityRepository.CapabilityRow;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService.IssuedCapability;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Atomic manager-side capability mutations.
 *
 * <p>For rotate/revoke the existing capability is locked first, matching the
 * public capability flow and preventing a capability/order lock inversion.
 * The canonical order (or archived row) is then locked before object
 * authorization, closing the reassignment-vs-mutation authorization race.</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewCheckCapabilityMutationService {

    private final ReviewCheckCapabilityService capabilityService;
    private final ReviewCheckCapabilityRepository capabilityRepository;
    private final OrderRepository orderRepository;
    private final OrderDetailsRepository orderDetailsRepository;
    private final ReviewCheckCapabilityResourceRepository resourceRepository;
    private final ManagerAccessService managerAccessService;
    private final UserRepository userRepository;

    @Transactional
    public IssuedCapability issue(
            Long orderId,
            UUID orderDetailId,
            Set<String> scopes,
            Integer expiresInDays,
            Authentication authentication
    ) {
        requireLockedResourceAccess(orderId, orderDetailId, authentication);
        return capabilityService.issue(
                orderDetailId,
                scopes,
                expiresInDays,
                actorId(authentication)
        );
    }

    @Transactional
    public IssuedCapability rotate(
            Long orderId,
            long capabilityId,
            UUID orderDetailId,
            Integer expiresInDays,
            Authentication authentication
    ) {
        requireLockedCapabilityBinding(capabilityId, orderDetailId);
        requireLockedResourceAccess(orderId, orderDetailId, authentication);
        return capabilityService.rotate(
                capabilityId,
                orderDetailId,
                expiresInDays,
                actorId(authentication)
        );
    }

    @Transactional
    public void revoke(
            Long orderId,
            long capabilityId,
            UUID orderDetailId,
            String reason,
            Authentication authentication
    ) {
        requireLockedCapabilityBinding(capabilityId, orderDetailId);
        requireLockedResourceAccess(orderId, orderDetailId, authentication);
        capabilityService.revoke(
                capabilityId,
                orderDetailId,
                actorId(authentication),
                reason
        );
    }

    private void requireLockedCapabilityBinding(long capabilityId, UUID orderDetailId) {
        CapabilityRow capability = capabilityRepository.findByIdForUpdate(capabilityId)
                .orElseThrow(ReviewCheckCapabilityMutationService::notFound);
        if (!"OPAQUE".equals(capability.tokenType())
                || !Objects.equals(orderDetailId, capability.orderDetailId())) {
            throw notFound();
        }
    }

    private void requireLockedResourceAccess(
            Long expectedOrderId,
            UUID orderDetailId,
            Authentication authentication
    ) {
        if (expectedOrderId == null || orderDetailId == null) {
            throw notFound();
        }

        if (orderRepository.findByIdForCounterUpdate(expectedOrderId).isPresent()) {
            if (!orderDetailsRepository.existsByIdAndOrder_Id(orderDetailId, expectedOrderId)) {
                throw notFound();
            }
            managerAccessService.requireOrderAccess(expectedOrderId, authentication);
            return;
        }

        ArchivedResourceBinding archived = resourceRepository
                .findArchivedByOrderDetailIdForUpdate(orderDetailId)
                .orElseThrow(ReviewCheckCapabilityMutationService::notFound);
        if (!Objects.equals(expectedOrderId, archived.orderId())
                || !managerAccessService.canAccessArchivedOrder(
                        archived.managerId(),
                        archived.workerId(),
                        authentication
                )) {
            throw notFound();
        }
    }

    private Long actorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw notFound();
        }
        User user = userRepository.findByUsername(authentication.getName()).orElseThrow(
                ReviewCheckCapabilityMutationService::notFound
        );
        if (user.getId() == null) {
            throw notFound();
        }
        return user.getId();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена");
    }
}
