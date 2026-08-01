package com.hunt.otziv.r_review.controller;

import com.hunt.otziv.archive.service.ReviewCheckArchiveService;
import com.hunt.otziv.archive.service.ReviewCheckArchiveService.ArchivedReviewCheck;
import com.hunt.otziv.manager.services.ManagerAccessService;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService.CapabilityMetadata;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService.IssuedCapability;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager/orders/{orderId}/review-check-capabilities")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
@Transactional
public class ApiReviewCheckCapabilityManagementController {

    private final ReviewCheckCapabilityService capabilityService;
    private final OrderDetailsService orderDetailsService;
    private final ReviewCheckArchiveService archiveService;
    private final ManagerAccessService managerAccessService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<IssuedCapability> issue(
            @PathVariable Long orderId,
            @RequestBody IssueRequest request,
            Authentication authentication
    ) {
        UUID orderDetailId = requiredOrderDetailId(request == null ? null : request.orderDetailId());
        requireResourceAccess(orderId, orderDetailId, authentication);
        IssuedCapability issued = capabilityService.issue(
                orderDetailId,
                request.scopes(),
                request.expiresInDays(),
                actorId(authentication)
        );
        return oneTimeSecret(issued);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CapabilityMetadata> list(
            @PathVariable Long orderId,
            @RequestParam UUID orderDetailId,
            Authentication authentication
    ) {
        requireResourceAccess(orderId, orderDetailId, authentication);
        return capabilityService.list(orderDetailId);
    }

    @PostMapping("/{capabilityId}/rotate")
    public ResponseEntity<IssuedCapability> rotate(
            @PathVariable Long orderId,
            @PathVariable Long capabilityId,
            @RequestBody RotateRequest request,
            Authentication authentication
    ) {
        UUID orderDetailId = requiredOrderDetailId(request == null ? null : request.orderDetailId());
        requireResourceAccess(orderId, orderDetailId, authentication);
        IssuedCapability issued = capabilityService.rotate(
                capabilityId,
                orderDetailId,
                request.expiresInDays(),
                actorId(authentication)
        );
        return oneTimeSecret(issued);
    }

    @PostMapping("/{capabilityId}/revoke")
    public ResponseEntity<Void> revoke(
            @PathVariable Long orderId,
            @PathVariable Long capabilityId,
            @RequestBody RevokeRequest request,
            Authentication authentication
    ) {
        UUID orderDetailId = requiredOrderDetailId(request == null ? null : request.orderDetailId());
        requireResourceAccess(orderId, orderDetailId, authentication);
        capabilityService.revoke(
                capabilityId,
                orderDetailId,
                actorId(authentication),
                request.reason()
        );
        return ResponseEntity.noContent().build();
    }

    private void requireResourceAccess(Long expectedOrderId, UUID orderDetailId, Authentication authentication) {
        if (expectedOrderId == null) {
            throw notFound();
        }

        try {
            OrderDetails live = orderDetailsService.getOrderDetailForReviewCheckById(orderDetailId);
            Long actualOrderId = live == null || live.getOrder() == null ? null : live.getOrder().getId();
            if (!Objects.equals(expectedOrderId, actualOrderId)) {
                throw notFound();
            }
            managerAccessService.requireOrderAccess(actualOrderId, authentication);
            return;
        } catch (UsernameNotFoundException ignored) {
            // The capability intentionally survives live -> archive moves.
        }

        ArchivedReviewCheck archived = archiveService.findByOrderDetailId(orderDetailId).orElseThrow(
                ApiReviewCheckCapabilityManagementController::notFound
        );
        if (!Objects.equals(expectedOrderId, archived.orderId()) || archived.companyId() == null) {
            throw notFound();
        }
        managerAccessService.requireCompanyAccess(archived.companyId(), authentication);
    }

    private Long actorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw notFound();
        }
        User user = userRepository.findByUsername(authentication.getName()).orElseThrow(
                ApiReviewCheckCapabilityManagementController::notFound
        );
        if (user.getId() == null) {
            throw notFound();
        }
        return user.getId();
    }

    private UUID requiredOrderDetailId(UUID orderDetailId) {
        if (orderDetailId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Проверка отзывов не указана");
        }
        return orderDetailId;
    }

    private ResponseEntity<IssuedCapability> oneTimeSecret(IssuedCapability issued) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(issued);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Проверка отзывов не найдена");
    }

    public record IssueRequest(
            UUID orderDetailId,
            Set<String> scopes,
            Integer expiresInDays
    ) {
    }

    public record RotateRequest(UUID orderDetailId, Integer expiresInDays) {
    }

    public record RevokeRequest(UUID orderDetailId, String reason) {
    }
}
