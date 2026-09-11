package com.hunt.otziv.p_products.board.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.p_products.application.WorkerStaffAccessPolicy;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Worker;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Shared scoped task reads for board pages and the overdue summary. */
@Service
@RequiredArgsConstructor
public class WorkerBoardTaskQueries {
    private static final String SECTION_BAD = "bad", SECTION_NAGUL = "nagul", ORDER_STATUS_UNPAID = "Не оплачено";
    private final WorkerStaffAccessPolicy staffAccess;
    private final ReviewService reviewService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final BadReviewTaskService badReviewTaskService;

    public Page<ReviewRecoveryTask> loadRecoveryTasks(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            LocalDate dueOnOrBefore
    ) {
        PageRequest pageable = PageRequest.of(pageNumber, pageSize, recoveryTaskSort(sortDirection));
        LocalDate date = Objects.requireNonNull(dueOnOrBefore, "dueOnOrBefore");

        if (selectedWorker != null) {
            return reviewRecoveryTaskService.getDueTasksToWorker(selectedWorker, date, keyword, pageable);
        }

        if (hasRole(authentication, "ADMIN")) {
            return reviewRecoveryTaskService.getDueTasksToAdmin(date, keyword, pageable);
        }
        if (hasRole(authentication, "OWNER")) {
            return reviewRecoveryTaskService.getDueTasksToOwner(staffAccess.resolveOwnerManagers(principal), date, keyword, pageable);
        }
        if (hasRole(authentication, "MANAGER")) {
            return reviewRecoveryTaskService.getDueTasksToManager(staffAccess.resolveManager(principal), date, keyword, pageable);
        }
        return reviewRecoveryTaskService.getDueTasksToWorker(staffAccess.resolveWorker(principal), date, keyword, pageable);
    }

    public Page<BadReviewTask> loadBadReviewTasks(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String keyword,
            int pageNumber,
            int pageSize,
            String sortDirection,
            LocalDate dueOnOrBefore
    ) {
        PageRequest pageable = PageRequest.of(pageNumber, pageSize, badReviewTaskSort(sortDirection));
        LocalDate date = Objects.requireNonNull(dueOnOrBefore, "dueOnOrBefore");

        if (selectedWorker != null) {
            return badReviewTaskService.getDueTasksToWorker(selectedWorker, date, keyword, pageable);
        }

        if (hasRole(authentication, "ADMIN")) {
            return badReviewTaskService.getDueTasksToAdmin(date, keyword, pageable);
        }
        if (hasRole(authentication, "OWNER")) {
            return badReviewTaskService.getDueTasksToOwner(staffAccess.resolveOwnerManagers(principal), date, keyword, pageable);
        }
        if (hasRole(authentication, "MANAGER")) {
            return badReviewTaskService.getDueTasksToManager(staffAccess.resolveManager(principal), date, keyword, pageable);
        }
        return badReviewTaskService.getDueTasksToWorker(staffAccess.resolveWorker(principal), date, keyword, pageable);
    }

    public Page<ReviewDTOOne> loadReviewPage(
            Principal principal,
            Authentication authentication,
            Worker selectedWorker,
            String section,
            int pageNumber,
            int pageSize,
            String sortDirection,
            String keyword,
            LocalDate dueOnOrBefore
    ) {
        LocalDate date = Objects.requireNonNull(dueOnOrBefore, "dueOnOrBefore");

        if (selectedWorker != null) {
            if (SECTION_BAD.equals(section)) {
                return reviewService.getAllReviewDTOByWorkerByOrderStatus(selectedWorker, ORDER_STATUS_UNPAID, pageNumber, pageSize, sortDirection, keyword);
            }
            if (SECTION_NAGUL.equals(section)) {
                return reviewService.getAllReviewDTOByWorkerByPublishToVigul(selectedWorker, date, pageNumber, pageSize, sortDirection, keyword);
            }
            return reviewService.getAllReviewDTOByWorkerByPublish(selectedWorker, date, pageNumber, pageSize, sortDirection, keyword);
        }

        if (SECTION_BAD.equals(section)) {
            if (hasRole(authentication, "ADMIN")) {
                return reviewService.getAllReviewDTOByOrderStatusToAdmin(ORDER_STATUS_UNPAID, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "OWNER")) {
                return reviewService.getAllReviewDTOByOwnerByOrderStatus(ORDER_STATUS_UNPAID, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "MANAGER")) {
                return reviewService.getAllReviewDTOByManagerByOrderStatus(ORDER_STATUS_UNPAID, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            return reviewService.getAllReviewDTOByWorkerByOrderStatus(ORDER_STATUS_UNPAID, principal, pageNumber, pageSize, sortDirection, keyword);
        }

        if (SECTION_NAGUL.equals(section)) {
            if (hasRole(authentication, "ADMIN")) {
                return reviewService.getAllReviewDTOAndDateToAdminToVigul(date, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "OWNER")) {
                return reviewService.getAllReviewDTOByOwnerByPublishToVigul(date, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            if (hasRole(authentication, "MANAGER")) {
                return reviewService.getAllReviewDTOByManagerByPublishToVigul(date, principal, pageNumber, pageSize, sortDirection, keyword);
            }
            return reviewService.getAllReviewDTOByWorkerByPublishToVigul(date, principal, pageNumber, pageSize, sortDirection, keyword);
        }

        if (hasRole(authentication, "ADMIN")) {
            return reviewService.getAllReviewDTOAndDateToAdmin(date, pageNumber, pageSize, sortDirection, keyword);
        }
        if (hasRole(authentication, "OWNER")) {
            return reviewService.getAllReviewDTOByOwnerByPublish(date, principal, pageNumber, pageSize, sortDirection, keyword);
        }
        if (hasRole(authentication, "MANAGER")) {
            return reviewService.getAllReviewDTOByManagerByPublish(date, principal, pageNumber, pageSize, sortDirection, keyword);
        }
        return reviewService.getAllReviewDTOByWorkerByPublish(date, principal, pageNumber, pageSize, sortDirection, keyword);
    }

    private Sort badReviewTaskSort(String sortDirection) {
        return "asc".equals(sortDirection)
                ? Sort.by("scheduledDate").descending().and(Sort.by("id").descending())
                : Sort.by("scheduledDate").ascending().and(Sort.by("id").ascending());
    }

    private Sort recoveryTaskSort(String sortDirection) {
        return "asc".equals(sortDirection)
                ? Sort.by("scheduledDate").descending().and(Sort.by("id").descending())
                : Sort.by("scheduledDate").ascending().and(Sort.by("id").ascending());
    }
    private boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }
}
