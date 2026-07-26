package com.hunt.otziv.manager_daily_summary.controller;

import com.hunt.otziv.manager_daily_summary.dto.ManagerReportDisputeResolutionRequest;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewAdminService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/manager-daily-summary/review-sessions")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiManagerReportReviewAdminController {

    private final ManagerReportReviewAdminService service;
    private final UserRepository userRepository;

    @PostMapping("/{reviewId}/resolve-dispute")
    public void resolveDispute(
            @PathVariable Long reviewId,
            @RequestBody ManagerReportDisputeResolutionRequest request,
            Principal principal
    ) {
        if (principal == null || principal.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        User actor = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        service.resolveDispute(
                reviewId,
                request == null ? null : request.action(),
                request == null ? null : request.comment(),
                actor
        );
    }
}
