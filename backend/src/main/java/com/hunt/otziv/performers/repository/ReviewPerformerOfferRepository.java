package com.hunt.otziv.performers.repository;

import com.hunt.otziv.performers.model.PerformerOfferStatus;
import com.hunt.otziv.performers.model.ReviewPerformerOffer;
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
public interface ReviewPerformerOfferRepository extends CrudRepository<ReviewPerformerOffer, Long> {

    @Query("""
        SELECT DISTINCT o
        FROM ReviewPerformerOffer o
        JOIN FETCH o.assignment a
        JOIN FETCH a.order ord
        LEFT JOIN FETCH ord.company
        LEFT JOIN FETCH a.review
        JOIN FETCH o.performer p
        JOIN FETCH p.user
        WHERE o.id = :offerId
    """)
    Optional<ReviewPerformerOffer> findByIdForAction(@Param("offerId") Long offerId);

    @Query("""
        SELECT DISTINCT o
        FROM ReviewPerformerOffer o
        JOIN FETCH o.assignment a
        JOIN FETCH o.performer p
        JOIN FETCH p.user
        WHERE o.status = com.hunt.otziv.performers.model.PerformerOfferStatus.OFFERED
          AND o.expiresAt <= :now
        ORDER BY o.expiresAt ASC, o.id ASC
    """)
    List<ReviewPerformerOffer> findExpired(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("""
        SELECT o
        FROM ReviewPerformerOffer o
        WHERE o.assignment.id = :assignmentId
          AND o.status IN :statuses
    """)
    List<ReviewPerformerOffer> findByAssignmentIdAndStatuses(
            @Param("assignmentId") Long assignmentId,
            @Param("statuses") Collection<PerformerOfferStatus> statuses
    );

    @Query("""
        SELECT DISTINCT o
        FROM ReviewPerformerOffer o
        JOIN FETCH o.assignment a
        JOIN FETCH a.order ord
        LEFT JOIN FETCH ord.company
        LEFT JOIN FETCH ord.status
        LEFT JOIN FETCH a.review
        LEFT JOIN FETCH a.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH a.city
        WHERE o.performer.id = :performerId
          AND o.status = com.hunt.otziv.performers.model.PerformerOfferStatus.OFFERED
        ORDER BY o.expiresAt ASC, o.id ASC
    """)
    List<ReviewPerformerOffer> findOfferedByPerformer(@Param("performerId") Long performerId);
}
