package com.hunt.otziv.manager_daily_summary.repository;

import com.hunt.otziv.manager_daily_summary.model.ManagerReviewCommitment;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagerReviewCommitmentRepository extends JpaRepository<ManagerReviewCommitment, Long> {
    @org.springframework.data.jpa.repository.Query("select c from ManagerReviewCommitment c where c.managerId=:manager "
            + "and c.workerId is not null and c.status in :statuses and ((c.dueAt is not null and c.dueAt<=:now) "
            + "or (c.dueAt is null and c.createdAt<=:createdBefore)) order by c.createdAt")
    List<ManagerReviewCommitment> findDueWorkerGoals(@org.springframework.data.repository.query.Param("manager") Long manager,
            @org.springframework.data.repository.query.Param("statuses") Collection<String> statuses,
            @org.springframework.data.repository.query.Param("now") LocalDateTime now,
            @org.springframework.data.repository.query.Param("createdBefore") LocalDateTime createdBefore);
    List<ManagerReviewCommitment> findByManagerIdAndWorkerIdAndGoalDateAndStatusIn(
            Long managerId,Long workerId,java.time.LocalDate goalDate,Collection<String> statuses);
    boolean existsByReviewIdAndQuestionIndex(Long reviewId, int questionIndex);
    List<ManagerReviewCommitment> findByManagerIdAndStatusInAndDueAtLessThanEqualOrderByDueAtAsc(
            Long managerId, Collection<String> statuses, LocalDateTime now);
    List<ManagerReviewCommitment> findBySourceTaskIdAndStatusIn(Long sourceTaskId, Collection<String> statuses);
    List<ManagerReviewCommitment> findByStatusInOrderByCheckedAtAsc(Collection<String> statuses, Pageable page);
    List<ManagerReviewCommitment> findTop20BySourceTaskIdOrderByCreatedAtDesc(Long sourceTaskId);
    List<ManagerReviewCommitment> findTop20BySourceTaskIdInOrderByCreatedAtDesc(Collection<Long> sourceTaskIds);
}
