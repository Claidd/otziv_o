package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerPayout;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformerPayoutRepository extends CrudRepository<PerformerPayout, Long> {
    boolean existsByAssignmentId(Long assignmentId);
    Optional<PerformerPayout> findByAssignmentId(Long assignmentId);
}
