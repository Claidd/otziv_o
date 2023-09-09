package com.hunt.otziv.r_review.services;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService{

    private final ReviewRepository reviewRepository;

    public Review save(Review review){
       return reviewRepository.save(review);
    }

    @Override
    public List<Review> getReviewsAllByOrderId(Long id) {
        return reviewRepository.findAllByOrderDetailsId(id);
    }
}
