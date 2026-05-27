package com.hunt.otziv.r_review.services;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.model.ReviewArchiveSourceReason;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewArchiveServiceImpl implements ReviewArchiveService{

    private final ReviewArchiveRepository reviewArchiveRepository;
    private final ReviewService reviewService;

    @Transactional
    public void saveNewReviewArchive(Long reviewId){
        saveNewReviewArchive(reviewId, ReviewArchiveSourceReason.UNKNOWN);
    }

    @Transactional
    public void saveNewReviewArchive(Long reviewId, String sourceReason){
        Review review = reviewService.getReviewById(reviewId);
        if (review != null) {
            if (isBlankOrPlaceholder(review.getText())) {
                log.info("4. Пустой или шаблонный отзыв в архив не сохранен");
                return;
            }
            ReviewArchive existingArchive = reviewArchiveRepository.findFirstByText(review.getText()).orElse(null);
            if (existingArchive == null) {
                ReviewArchive reviewArchive = new ReviewArchive();
                reviewArchive.setText(review.getText());
                reviewArchive.setAnswer(review.getAnswer());
                reviewArchive.setCategory(review.getCategory());
                reviewArchive.setSubCategory(review.getSubCategory());
                applySource(reviewArchive, review, sourceReason);
                reviewArchiveRepository.save(reviewArchive);
                log.info("4. Отзыв в архив сохранен");
            } else {
                enrichExistingSource(existingArchive, review, sourceReason);
                log.info("4. Отзыв с таким текстом уже существует, отзыв в архив не сохранен");
            }
        } else {
            log.info("4. Отзыв по id не был найден, отзыв в архив не сохранен");
        }
    }

    @Override
    public boolean existsByText(String text) {
        if (isBlankOrPlaceholder(text)) {
            return false;
        }
        return reviewArchiveRepository.existsByText(text);
    }

    @Override
    public boolean existsByTextExcludingOwnSource(String text, Long reviewId, Long orderId) {
        if (isBlankOrPlaceholder(text)) {
            return false;
        }
        return reviewArchiveRepository.existsByTextExcludingOwnSource(text, reviewId, orderId);
    }

    @Override
    public Iterable<ReviewArchive> findAllReviews() {
        return reviewArchiveRepository.findAll();
    }

    private void enrichExistingSource(ReviewArchive archive, Review review, String sourceReason) {
        String normalizedReason = normalizeSourceReason(sourceReason);
        boolean forcePublishedSource = ReviewArchiveSourceReason.PUBLISHED.equals(normalizedReason)
                && !ReviewArchiveSourceReason.PUBLISHED.equals(archive.getSourceReason());
        boolean missingSource = archive.getSourceReview() == null
                || archive.getSourceOrder() == null
                || archive.getSourceReason() == null
                || archive.getSourceReason().isBlank();

        if (!forcePublishedSource && !missingSource) {
            return;
        }

        if (forcePublishedSource) {
            archive.setSourceReview(review);
            archive.setSourceOrder(resolveOrder(review));
            archive.setSourceReason(ReviewArchiveSourceReason.PUBLISHED);
        } else {
            applySource(archive, review, normalizedReason);
        }
        reviewArchiveRepository.save(archive);
    }

    private void applySource(ReviewArchive archive, Review review, String sourceReason) {
        if (archive.getSourceReview() == null) {
            archive.setSourceReview(review);
        }
        if (archive.getSourceOrder() == null) {
            archive.setSourceOrder(resolveOrder(review));
        }
        if (archive.getSourceReason() == null || archive.getSourceReason().isBlank()) {
            archive.setSourceReason(normalizeSourceReason(sourceReason));
        }
    }

    private Order resolveOrder(Review review) {
        OrderDetails details = review.getOrderDetails();
        return details == null ? null : details.getOrder();
    }

    private String normalizeSourceReason(String sourceReason) {
        if (sourceReason == null || sourceReason.isBlank()) {
            return ReviewArchiveSourceReason.UNKNOWN;
        }
        return sourceReason;
    }


//    @Transactional
//    public void saveNewReviewArchive(Long reviewId){
//        Review review = reviewService.getReviewById(reviewId);
//        if (review != null){
//            ReviewArchive reviewArchive = new ReviewArchive();
//            reviewArchive.setText(review.getText());
//            reviewArchive.setAnswer(review.getAnswer());
//            reviewArchive.setCategory(review.getCategory());
//            reviewArchive.setSubCategory(review.getSubCategory());
//            reviewArchiveRepository.save(reviewArchive);
//            log.info("7. Отзыв в архив сохранен");
//        }
//        else {
//            log.info("7. Отзыв по ид не ьыл найдет, отзыв в архив не сохранен");
//        }
//    }


}
