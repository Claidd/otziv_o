package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerReviewCommitment;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerReviewCommitmentRepository extends JpaRepository<ManagerReviewCommitment, Long> {
    boolean existsByReviewIdAndQuestionIndex(Long reviewId, int questionIndex);
    List<ManagerReviewCommitment> findByManagerIdAndStatusInAndDueAtLessThanEqualOrderByDueAtAsc(
            Long managerId, Collection<String> statuses, LocalDateTime now);
    List<ManagerReviewCommitment> findBySourceTaskIdAndStatusIn(Long sourceTaskId, Collection<String> statuses);
    List<ManagerReviewCommitment> findByStatusInOrderByCheckedAtAsc(Collection<String> statuses, Pageable page);
    List<ManagerReviewCommitment> findTop20BySourceTaskIdOrderByCreatedAtDesc(Long sourceTaskId);
    List<ManagerReviewCommitment> findTop20BySourceTaskIdInOrderByCreatedAtDesc(Collection<Long> sourceTaskIds);
}
