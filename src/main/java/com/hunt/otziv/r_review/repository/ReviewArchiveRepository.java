package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.r_review.model.ReviewArchive;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewArchiveRepository extends CrudRepository<ReviewArchive, Long> {
}
