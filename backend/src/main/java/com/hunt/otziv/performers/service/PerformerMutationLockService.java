package com.hunt.otziv.performers.service;

import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import com.hunt.otziv.performers.model.ReviewPerformerOffer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Lock order matches the existing live-order aggregate: order -> assignment -> offer/profile.
 * Scheduler batches must call each aggregate in its own transaction. */
@Service
@RequiredArgsConstructor
public class PerformerMutationLockService {
    private final OrderAggregateMutationLockService orderLocks;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void order(Long orderId) { orderLocks.lock(orderId); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void review(Long reviewId) { orderLocks.lockForReview(reviewId); }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewPerformerAssignment assignment(Long id) {
        Long orderId = scalar("SELECT order_id FROM review_performer_assignments WHERE assignment_id = ?", id);
        orderLocks.lock(orderId);
        ReviewPerformerAssignment assignment = current(ReviewPerformerAssignment.class, id);
        if (!orderId.equals(assignment.getOrder().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав задания изменился");
        }
        return assignment;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewPerformerOffer offer(Long id) {
        Long assignmentId = scalar("SELECT assignment_id FROM review_performer_offers WHERE offer_id = ?", id);
        assignment(assignmentId);
        ReviewPerformerOffer offer = current(ReviewPerformerOffer.class, id);
        if (!assignmentId.equals(offer.getAssignment().getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Состав предложения изменился");
        }
        return offer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PerformerProfile profile(Long id) { return current(PerformerProfile.class, id); }

    private Long scalar(String sql, Long id) {
        return jdbc.query(sql, (rs, row) -> rs.getLong(1), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено"));
    }

    private <T> T current(Class<T> type, Long id) {
        T entity = entityManager.find(type, id);
        if (entity == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задание не найдено");
        // Refresh stale managed snapshots only on the first acquisition. Nested use
        // cases must preserve the caller's already locked, unflushed modifications.
        if (entityManager.getLockMode(entity) != LockModeType.PESSIMISTIC_WRITE) {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        }
        return entity;
    }
}
