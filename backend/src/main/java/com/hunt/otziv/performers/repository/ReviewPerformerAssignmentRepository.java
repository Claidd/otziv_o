package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerAssignmentStatus;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewPerformerAssignmentRepository extends CrudRepository<ReviewPerformerAssignment, Long> {

    boolean existsByReviewId(Long reviewId);

    Optional<ReviewPerformerAssignment> findByReviewId(Long reviewId);

    @Query("""
        SELECT DISTINCT a
        FROM ReviewPerformerAssignment a
        JOIN FETCH a.order o
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH a.orderDetails
        JOIN FETCH a.review r
        LEFT JOIN FETCH a.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH a.city
        LEFT JOIN FETCH a.performer p
        LEFT JOIN FETCH p.user
        WHERE a.id = :assignmentId
    """)
    Optional<ReviewPerformerAssignment> findByIdForDetails(@Param("assignmentId") Long assignmentId);

    @Query("""
        SELECT DISTINCT a
        FROM ReviewPerformerAssignment a
        JOIN FETCH a.order o
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.status
        JOIN FETCH a.review r
        LEFT JOIN FETCH a.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH a.city
        LEFT JOIN FETCH a.performer p
        LEFT JOIN FETCH p.user
        WHERE p.id = :performerId
          AND a.status IN :statuses
        ORDER BY a.updatedAt DESC, a.id DESC
    """)
    List<ReviewPerformerAssignment> findByPerformerForBoard(
            @Param("performerId") Long performerId,
            @Param("statuses") Collection<PerformerAssignmentStatus> statuses
    );

    @Query("""
        SELECT DISTINCT a
        FROM ReviewPerformerAssignment a
        JOIN FETCH a.order o
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.status
        JOIN FETCH a.review r
        LEFT JOIN FETCH a.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH a.city
        LEFT JOIN FETCH a.performer p
        LEFT JOIN FETCH p.user
        WHERE a.status IN :statuses
        ORDER BY a.updatedAt DESC, a.id DESC
    """)
    List<ReviewPerformerAssignment> findAllForAdmin(@Param("statuses") Collection<PerformerAssignmentStatus> statuses);

    @Query("""
        SELECT DISTINCT a
        FROM ReviewPerformerAssignment a
        JOIN FETCH a.order o
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.status
        JOIN FETCH a.review r
        LEFT JOIN FETCH a.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH a.city
        WHERE a.status IN :statuses
        ORDER BY a.createdAt ASC, a.id ASC
    """)
    List<ReviewPerformerAssignment> findQueue(
            @Param("statuses") Collection<PerformerAssignmentStatus> statuses,
            Pageable pageable
    );

    @Query("""
        SELECT a
        FROM ReviewPerformerAssignment a
        JOIN FETCH a.performer p
        JOIN FETCH p.user
        WHERE a.status = com.hunt.otziv.performers.model.PerformerAssignmentStatus.WAITING_PUBLICATION
          AND a.publishAvailableAt IS NOT NULL
          AND a.publishAvailableAt <= :now
        ORDER BY a.publishAvailableAt ASC, a.id ASC
    """)
    List<ReviewPerformerAssignment> findReadyToPublish(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("""
        SELECT COUNT(a.id)
        FROM ReviewPerformerAssignment a
        WHERE a.order.id = :orderId
          AND a.status NOT IN :terminalStatuses
    """)
    long countNotInStatusesByOrderId(
            @Param("orderId") Long orderId,
            @Param("terminalStatuses") Collection<PerformerAssignmentStatus> terminalStatuses
    );

    @Query("""
        SELECT COUNT(a.id)
        FROM ReviewPerformerAssignment a
        WHERE a.order.id = :orderId
    """)
    long countByOrderId(@Param("orderId") Long orderId);
}
