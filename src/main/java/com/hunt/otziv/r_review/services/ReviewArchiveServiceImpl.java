package com.hunt.otziv.r_review.services;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchive;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewArchiveServiceImpl implements ReviewArchiveService{

    private final ReviewArchiveRepository reviewArchiveRepository;
    private final ReviewService reviewService;

    @Transactional
    public void saveNewReviewArchive(Long reviewId){
        Review review = reviewService.getReviewById(reviewId);
        if (review != null){
            ReviewArchive reviewArchive = new ReviewArchive();
            reviewArchive.setText(review.getText());
            reviewArchive.setAnswer(review.getAnswer());
            reviewArchive.setCategory(review.getCategory());
            reviewArchive.setSubCategory(review.getSubCategory());
            reviewArchiveRepository.save(reviewArchive);
            log.info("7. Отзыв в архив сохранен");
        }
        else {
            log.info("7. Отзыв по ид не ьыл найдет, отзыв в архив не сохранен");
        }

    }
}
