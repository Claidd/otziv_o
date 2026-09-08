package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import java.util.Locale;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Review source, assignment and network policy shared by every worker review command. */
@Service
@RequiredArgsConstructor
public class WorkerReviewAccessPolicy {
    private static final String SECTION_NEW="new";
    private static final String SECTION_CORRECT="correct";
    private static final String SECTION_NAGUL="nagul";

    private static final String SECTION_PUBLISH="publish";

    private static final String ORDER_STATUS_NEW="Новый", ORDER_STATUS_CORRECT="Коррекция";

    private final ReviewService reviewService;
    private final WorkerCellularAccessService workerCellularAccessService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    public void enforceReviewSourceAccess(Long reviewId, String sourceSection, Authentication authentication) {
        enforceReviewSourceAccess(reviewService.getReviewById(reviewId), sourceSection, authentication);
    }

    public void enforceReviewSourceAccess(Review review, String sourceSection, Authentication authentication) {
        if (review != null && review.getId() != null) {
            assignmentMutationGuardService.assertReview(review.getId(), authentication);
        }
        String normalized = safe(sourceSection).trim().toLowerCase(Locale.ROOT);
        String orderSection = reviewOrderSection(review);
        if (orderSection != null) {
            workerCellularAccessService.enforceSection(orderSection, authentication);
            return;
        }
        if (WorkerCellularAccessService.PROTECTED_SECTIONS.contains(normalized)) {
            workerCellularAccessService.enforceSection(normalized, authentication);
            return;
        }
        workerCellularAccessService.enforceSection(
                review != null && review.isVigul() ? SECTION_PUBLISH : SECTION_NAGUL, authentication
        );
    }

    private String reviewOrderSection(Review review) {
        if (review == null
                || review.getOrderDetails() == null
                || review.getOrderDetails().getOrder() == null
                || review.getOrderDetails().getOrder().getStatus() == null) {
            return null;
        }
        String status = safe(review.getOrderDetails().getOrder().getStatus().getTitle()).trim();
        if (ORDER_STATUS_NEW.equalsIgnoreCase(status)) {
            return SECTION_NEW;
        }
        if (ORDER_STATUS_CORRECT.equalsIgnoreCase(status)) {
            return SECTION_CORRECT;
        }
        return null;
    }
}
