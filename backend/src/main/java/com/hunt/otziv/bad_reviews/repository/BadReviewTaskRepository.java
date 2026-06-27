package com.hunt.otziv.bad_reviews.repository;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BadReviewTaskRepository extends CrudRepository<BadReviewTask, Long> {

    @Query("""
        SELECT t
        FROM BadReviewTask t
        LEFT JOIN FETCH t.order o
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.filial ofi
        LEFT JOIN FETCH ofi.city
        LEFT JOIN FETCH o.manager om
        LEFT JOIN FETCH om.user
        LEFT JOIN FETCH t.sourceReview r
        LEFT JOIN FETCH t.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH t.bot b
        WHERE t.id = :taskId
    """)
    Optional<BadReviewTask> findByIdForMutation(@Param("taskId") Long taskId);

    @Query("""
        SELECT DISTINCT t
        FROM BadReviewTask t
        LEFT JOIN FETCH t.sourceReview r
        LEFT JOIN FETCH r.bot rb
        LEFT JOIN FETCH rb.status
        LEFT JOIN FETCH t.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH t.bot b
        LEFT JOIN FETCH b.status
        WHERE t.order.id = :orderId
        ORDER BY t.created DESC, t.id DESC
    """)
    List<BadReviewTask> findAllByOrderIdOrderByCreatedDesc(@Param("orderId") Long orderId);

    List<BadReviewTask> findAllByOrderIdAndStatus(Long orderId, BadReviewTaskStatus status);

    @Modifying
    @Query("DELETE FROM BadReviewTask t WHERE t.order.id = :orderId")
    int deleteAllByOrderId(@Param("orderId") Long orderId);

    @Modifying
    @Query("DELETE FROM BadReviewTask t WHERE t.order.id = :orderId AND t.status = :status")
    int deleteAllByOrderIdAndStatus(@Param("orderId") Long orderId, @Param("status") BadReviewTaskStatus status);

    boolean existsByOrderIdAndSourceReviewIdAndStatusIn(
            Long orderId,
            Long sourceReviewId,
            Collection<BadReviewTaskStatus> statuses
    );

    @Query("""
        SELECT DISTINCT t.bot.id
        FROM BadReviewTask t
        WHERE t.status = :status
          AND t.bot IS NOT NULL
          AND t.bot.id IS NOT NULL
          AND t.bot.id <> 1
          AND (:excludedTaskId IS NULL OR t.id <> :excludedTaskId)
    """)
    Set<Long> findBotIdsByStatus(
            @Param("status") BadReviewTaskStatus status,
            @Param("excludedTaskId") Long excludedTaskId
    );

    @Query("""
        SELECT t.order.id, t.status, COUNT(t.id), COALESCE(SUM(t.price), 0)
        FROM BadReviewTask t
        WHERE t.order.id IN :orderIds
        GROUP BY t.order.id, t.status
    """)
    List<Object[]> summarizeByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    @Query("""
        SELECT t.status, COUNT(t.id), COALESCE(SUM(t.price), 0)
        FROM BadReviewTask t
        WHERE t.order.id = :orderId
        GROUP BY t.status
    """)
    List<Object[]> summarizeByOrderId(@Param("orderId") Long orderId);

    @Query(
            value = """
                SELECT DISTINCT t
                FROM BadReviewTask t
                JOIN FETCH t.order o
                LEFT JOIN FETCH o.company c
                LEFT JOIN FETCH o.status
                LEFT JOIN FETCH o.manager om
                LEFT JOIN FETCH om.user
                LEFT JOIN FETCH t.sourceReview r
                LEFT JOIN FETCH r.category
                LEFT JOIN FETCH r.subCategory
                LEFT JOIN FETCH r.product rp
                LEFT JOIN FETCH rp.productCategory
                LEFT JOIN FETCH r.bot rb
                LEFT JOIN FETCH rb.status
                LEFT JOIN FETCH r.worker rw
                LEFT JOIN FETCH rw.user
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH f.company
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product dp
                LEFT JOIN FETCH dp.productCategory
                LEFT JOIN FETCH d.order ro
                LEFT JOIN FETCH ro.company
                LEFT JOIN FETCH ro.status
                LEFT JOIN FETCH ro.manager rm
                LEFT JOIN FETCH rm.user
                LEFT JOIN FETCH t.worker w
                LEFT JOIN FETCH w.user
                LEFT JOIN FETCH t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """,
            countQuery = """
                SELECT COUNT(t.id)
                FROM BadReviewTask t
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<BadReviewTask> findDueTasksToAdmin(
            @Param("status") BadReviewTaskStatus status,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT DISTINCT t
                FROM BadReviewTask t
                JOIN FETCH t.order o
                LEFT JOIN FETCH o.company c
                LEFT JOIN FETCH o.status
                LEFT JOIN FETCH o.manager om
                LEFT JOIN FETCH om.user
                LEFT JOIN FETCH t.sourceReview r
                LEFT JOIN FETCH r.category
                LEFT JOIN FETCH r.subCategory
                LEFT JOIN FETCH r.product rp
                LEFT JOIN FETCH rp.productCategory
                LEFT JOIN FETCH r.bot rb
                LEFT JOIN FETCH rb.status
                LEFT JOIN FETCH r.worker rw
                LEFT JOIN FETCH rw.user
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH f.company
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product dp
                LEFT JOIN FETCH dp.productCategory
                LEFT JOIN FETCH d.order ro
                LEFT JOIN FETCH ro.company
                LEFT JOIN FETCH ro.status
                LEFT JOIN FETCH ro.manager rm
                LEFT JOIN FETCH rm.user
                LEFT JOIN FETCH t.worker w
                LEFT JOIN FETCH w.user
                LEFT JOIN FETCH t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND o.manager IN :managers
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """,
            countQuery = """
                SELECT COUNT(t.id)
                FROM BadReviewTask t
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND o.manager IN :managers
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<BadReviewTask> findDueTasksToOwner(
            @Param("managers") Collection<Manager> managers,
            @Param("status") BadReviewTaskStatus status,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT DISTINCT t
                FROM BadReviewTask t
                JOIN FETCH t.order o
                LEFT JOIN FETCH o.company c
                LEFT JOIN FETCH o.status
                LEFT JOIN FETCH o.manager om
                LEFT JOIN FETCH om.user
                LEFT JOIN FETCH t.sourceReview r
                LEFT JOIN FETCH r.category
                LEFT JOIN FETCH r.subCategory
                LEFT JOIN FETCH r.product rp
                LEFT JOIN FETCH rp.productCategory
                LEFT JOIN FETCH r.bot rb
                LEFT JOIN FETCH rb.status
                LEFT JOIN FETCH r.worker rw
                LEFT JOIN FETCH rw.user
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH f.company
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product dp
                LEFT JOIN FETCH dp.productCategory
                LEFT JOIN FETCH d.order ro
                LEFT JOIN FETCH ro.company
                LEFT JOIN FETCH ro.status
                LEFT JOIN FETCH ro.manager rm
                LEFT JOIN FETCH rm.user
                LEFT JOIN FETCH t.worker w
                LEFT JOIN FETCH w.user
                LEFT JOIN FETCH t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND o.manager = :manager
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """,
            countQuery = """
                SELECT COUNT(t.id)
                FROM BadReviewTask t
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND o.manager = :manager
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<BadReviewTask> findDueTasksToManager(
            @Param("manager") Manager manager,
            @Param("status") BadReviewTaskStatus status,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT DISTINCT t
                FROM BadReviewTask t
                JOIN FETCH t.order o
                LEFT JOIN FETCH o.company c
                LEFT JOIN FETCH o.status
                LEFT JOIN FETCH o.manager om
                LEFT JOIN FETCH om.user
                LEFT JOIN FETCH t.sourceReview r
                LEFT JOIN FETCH r.category
                LEFT JOIN FETCH r.subCategory
                LEFT JOIN FETCH r.product rp
                LEFT JOIN FETCH rp.productCategory
                LEFT JOIN FETCH r.bot rb
                LEFT JOIN FETCH rb.status
                LEFT JOIN FETCH r.worker rw
                LEFT JOIN FETCH rw.user
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH f.company
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product dp
                LEFT JOIN FETCH dp.productCategory
                LEFT JOIN FETCH d.order ro
                LEFT JOIN FETCH ro.company
                LEFT JOIN FETCH ro.status
                LEFT JOIN FETCH ro.manager rm
                LEFT JOIN FETCH rm.user
                LEFT JOIN FETCH t.worker w
                LEFT JOIN FETCH w.user
                LEFT JOIN FETCH t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND t.worker = :worker
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """,
            countQuery = """
                SELECT COUNT(t.id)
                FROM BadReviewTask t
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND t.scheduledDate <= :date
                  AND t.worker = :worker
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<BadReviewTask> findDueTasksToWorker(
            @Param("worker") Worker worker,
            @Param("status") BadReviewTaskStatus status,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    long countByStatusAndScheduledDateLessThanEqual(BadReviewTaskStatus status, LocalDate scheduledDate);

    long countByStatusAndScheduledDateLessThanEqualAndWorker(
            BadReviewTaskStatus status,
            LocalDate scheduledDate,
            Worker worker
    );

    long countByStatusAndScheduledDateLessThanEqualAndOrderManager(
            BadReviewTaskStatus status,
            LocalDate scheduledDate,
            Manager manager
    );

    long countByStatusAndScheduledDateLessThanEqualAndOrderManagerIn(
            BadReviewTaskStatus status,
            LocalDate scheduledDate,
            Collection<Manager> managers
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE BadReviewTask t
        SET t.scheduledDate = :newDate
        WHERE t.status = :status
          AND t.scheduledDate < :newDate
    """)
    int actualizeActiveTasksBefore(
            @Param("status") BadReviewTaskStatus status,
            @Param("newDate") LocalDate newDate
    );

    @Query("""
        SELECT DISTINCT t
        FROM BadReviewTask t
        LEFT JOIN FETCH t.order o
        LEFT JOIN FETCH o.worker ow
        LEFT JOIN FETCH ow.user
        LEFT JOIN FETCH o.manager om
        LEFT JOIN FETCH om.user
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH t.sourceReview
        LEFT JOIN FETCH t.worker w
        LEFT JOIN FETCH w.user
        WHERE t.status = :status
          AND t.completedDate BETWEEN :from AND :to
    """)
    List<BadReviewTask> findDoneForGamificationBackfill(@Param("status") BadReviewTaskStatus status,
                                                        @Param("from") LocalDate from,
                                                        @Param("to") LocalDate to);
}
