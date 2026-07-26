package com.hunt.otziv.manager_daily_summary.controller;

import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewAccessPolicy;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewCheckInService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager-report-review")
public class ApiManagerReportReviewAccessController {

    private final ManagerReportReviewAccessPolicy accessPolicy;
    private final ManagerReportReviewCheckInService checkInService;
    private final UserRepository userRepository;

    @GetMapping("/access-state")
    @PreAuthorize("hasRole('MANAGER')")
    public ManagerReportReviewAccessPolicy.AccessState accessState(Principal principal) {
        return accessPolicy.state(currentUser(principal));
    }

    @PostMapping("/check-in")
    @PreAuthorize("hasRole('MANAGER')")
    public ManagerReportReviewAccessPolicy.AccessState checkIn(Principal principal) {
        return checkInService.checkIn(currentUser(principal));
    }

    private User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
