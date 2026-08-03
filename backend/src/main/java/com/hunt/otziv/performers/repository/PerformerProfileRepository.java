package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface PerformerProfileRepository extends CrudRepository<PerformerProfile, Long> {

    Optional<PerformerProfile> findByUserId(Long userId);

    Optional<PerformerProfile> findByTelegramLinkToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p
        FROM PerformerProfile p
        JOIN FETCH p.user
        WHERE p.telegramLinkToken = :token
    """)
    Optional<PerformerProfile> findByTelegramLinkTokenForUpdate(@Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE PerformerProfile p
        SET p.status = com.hunt.otziv.performers.model.PerformerProfileStatus.REJECTED,
            p.telegramLinkToken = null,
            p.blockReason = 'Срок публичной заявки истёк'
        WHERE p.status = com.hunt.otziv.performers.model.PerformerProfileStatus.NEW
          AND p.registrationExpiresAt IS NOT NULL
          AND p.registrationExpiresAt <= :now
    """)
    int expirePendingRegistrations(@Param("now") LocalDateTime now);

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
          AND (
              :companyId IS NULL
              OR NOT EXISTS (
                  SELECT sameCompany.id
                  FROM ReviewPerformerAssignment sameCompany
                  WHERE sameCompany.performer = p
                    AND sameCompany.order.company.id = :companyId
                    AND sameCompany.status NOT IN (
                        com.hunt.otziv.performers.model.PerformerAssignmentStatus.REJECTED,
                        com.hunt.otziv.performers.model.PerformerAssignmentStatus.CANCELLED
                    )
              )
          )
          AND (
              :companyId IS NULL
              OR NOT EXISTS (
                  SELECT sameCompanyOffer.id
                  FROM ReviewPerformerOffer sameCompanyOffer
                  WHERE sameCompanyOffer.performer = p
                    AND sameCompanyOffer.assignment.order.company.id = :companyId
                    AND sameCompanyOffer.status IN (
                        com.hunt.otziv.performers.model.PerformerOfferStatus.OFFERED,
                        com.hunt.otziv.performers.model.PerformerOfferStatus.ACCEPTED
                    )
              )
          )
          AND NOT EXISTS (
              SELECT offered.id
              FROM ReviewPerformerOffer offered
              WHERE offered.performer = p
                AND offered.assignment.id = :assignmentId
          )
        ORDER BY
          CASE
              WHEN c.id = :cityId THEN 0
              WHEN EXISTS (
                  SELECT priorityCity.id
                  FROM PerformerCity priorityCity
                  WHERE priorityCity.performer = p
                    AND priorityCity.city.id = :cityId
                    AND priorityCity.active = true
              ) THEN 0
              ELSE 1
          END ASC,
          p.rating DESC,
          p.reliabilityScore DESC,
          p.completedCount DESC,
          p.id ASC
    """)
    List<PerformerProfile> findOfferCandidates(
            @Param("cityId") Long cityId,
            @Param("orderId") Long orderId,
            @Param("companyId") Long companyId,
            @Param("assignmentId") Long assignmentId,
            @Param("status") PerformerProfileStatus status,
            Pageable pageable
    );
}
