package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.api.ReviewPhotoRecords;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.photo.ReviewPhotoReferencePolicy;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewPhotoRecordService implements ReviewPhotoRecords {
    private final WorkerAssignmentMutationGuardService access;
    private final ReviewRepository reviews;
    private final ReviewPhotoReferencePolicy references;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAccess(long reviewId, Long expectedOrderId, Authentication actor) {
        long actualOrder = access.requireReviewOrder(reviewId, actor);
        if (expectedOrderId != null && !Objects.equals(expectedOrderId, actualOrder)) {
            throw new ReviewPhotoRecords.Unavailable("Отзыв не найден в этом заказе");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String replace(long reviewId, Long expectedOrderId, String url, Authentication actor) {
        requireAccess(reviewId, expectedOrderId, actor);
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> new ReviewPhotoRecords.Unavailable("Отзыв не найден"));
        references.requireAssignable(url);
        String previous = review.getUrl();
        review.setUrl(url);
        reviews.save(review);
        return previous;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public boolean retireForDeletion(String url) {
        return references.retireForDeletion(url);
    }
}
