package com.hunt.otziv.manager_control.controller;

import com.hunt.otziv.manager_control.service.ManagerAutomationFailureService;
import com.hunt.otziv.manager_control.service.ManagerControlAccessPolicy;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only queue: moving responsibility never changes the historical delivery outcome. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class AdminDeliveryReviewController {
    private final ManagerControlAccessPolicy access;
    private final ManagerAutomationFailureService failures;

    @GetMapping("/api/admin/manager-control/delivery-reviews")
    public List<ManagerAutomationFailureService.SystemDeliveryReview> list(Principal principal, Authentication authentication) {
        return access.visibleManagers(principal, authentication).stream()
                .flatMap(manager -> failures.systemDeliveryReviews(manager).stream()).toList();
    }
}
