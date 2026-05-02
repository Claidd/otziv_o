package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.r_review.model.Amount;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AmountRepository extends CrudRepository<Amount, Long> {
}
