package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.r_review.model.Review;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends CrudRepository<Review, Long> {

    List<Review> findAllByOrderDetailsId(Long orderDetailsId);
}
