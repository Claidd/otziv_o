package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformerProfileRepository extends CrudRepository<PerformerProfile, Long> {

    Optional<PerformerProfile> findByUserId(Long userId);

    Optional<PerformerProfile> findByTelegramLinkToken(String token);

    @Query("""
        SELECT DISTINCT p
        FROM PerformerProfile p
        JOIN FETCH p.user u
        LEFT JOIN FETCH p.city c
        WHERE p.status IN :statuses
        ORDER BY p.createdAt DESC, p.id DESC
    """)
    List<PerformerProfile> findAllForAdmin(@Param("statuses") Collection<PerformerProfileStatus> statuses);

    @Query("""
        SELECT DISTINCT p
        FROM PerformerProfile p
        JOIN FETCH p.user u
        LEFT JOIN FETCH p.city c
        WHERE p.status = :status
          AND u.active = true
          AND u.telegramChatId IS NOT NULL
          AND (
              c.id = :cityId
              OR EXISTS (
                  SELECT pc.id
                  FROM PerformerCity pc
                  WHERE pc.performer = p
                    AND pc.city.id = :cityId
                    AND pc.active = true
              )
          )
          AND (
              SELECT COUNT(a.id)
              FROM ReviewPerformerAssignment a
              WHERE a.performer = p
                AND a.status IN (
                    com.hunt.otziv.performers.model.PerformerAssignmentStatus.ACCEPTED,
                    com.hunt.otziv.performers.model.PerformerAssignmentStatus.WALKED,
                    com.hunt.otziv.performers.model.PerformerAssignmentStatus.WAITING_PUBLICATION,
                    com.hunt.otziv.performers.model.PerformerAssignmentStatus.PUBLISHED_CLAIMED
                )
          ) < p.maxActiveTasks
          AND NOT EXISTS (
              SELECT sameOrder.id
              FROM ReviewPerformerAssignment sameOrder
              WHERE sameOrder.performer = p
                AND sameOrder.order.id = :orderId
                AND sameOrder.status NOT IN (
                    com.hunt.otziv.performers.model.PerformerAssignmentStatus.REJECTED,
                    com.hunt.otziv.performers.model.PerformerAssignmentStatus.CANCELLED
                )
          )
          AND NOT EXISTS (
              SELECT offered.id
              FROM ReviewPerformerOffer offered
              WHERE offered.performer = p
                AND offered.assignment.id = :assignmentId
          )
        ORDER BY p.rating DESC, p.reliabilityScore DESC, p.completedCount DESC, p.id ASC
    """)
    List<PerformerProfile> findOfferCandidates(
            @Param("cityId") Long cityId,
            @Param("orderId") Long orderId,
            @Param("assignmentId") Long assignmentId,
            @Param("status") PerformerProfileStatus status,
            Pageable pageable
    );
}
