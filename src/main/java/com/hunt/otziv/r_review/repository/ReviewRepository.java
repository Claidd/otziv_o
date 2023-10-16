package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRepository extends CrudRepository<Review, Long> {

    List<Review> findAllByOrderDetailsId(UUID orderDetailsId);

    @Query("SELECT r FROM Review r WHERE r.publishedDate <= :localDate AND r.publish = false")
    List<Review> findAllByPublishedDateAndPublish(@Param("localDate") LocalDate localDate);

    @Query("SELECT r FROM Review r WHERE r.worker = :worker AND r.publishedDate <= :localDate AND r.publish = false")
    List<Review> findAllByWorkerAndPublishedDateAndPublish(@Param("worker") Worker worker, @Param("localDate") LocalDate localDate);


    List<Review> findAllByPublishedDateLessThanEqualAndPublishIsFalse(LocalDate localDate);
//    @Query("SELECT r FROM Review r WHERE r.publishDate <= :localDate AND r.publish = false")
//    List<Review> findAllByPublishDateAndPublish(@Param("localDate") LocalDate localDate);
}
