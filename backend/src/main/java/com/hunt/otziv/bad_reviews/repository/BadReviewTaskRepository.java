package com.hunt.otziv.bad_reviews.repository;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface BadReviewTaskRepository extends CrudRepository<BadReviewTask, Long> {

    List<BadReviewTask> findAllByOrderIdOrderByCreatedDesc(Long orderId);

    List<BadReviewTask> findAllByOrderIdAndStatus(Long orderId, BadReviewTaskStatus status);

    boolean existsByOrderIdAndSourceReviewIdAndStatusIn(
            Long orderId,
            Long sourceReviewId,
            Collection<BadReviewTaskStatus> statuses
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
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product
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
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product
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
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product
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
                LEFT JOIN FETCH r.filial f
                LEFT JOIN FETCH f.city city
                LEFT JOIN FETCH r.orderDetails d
                LEFT JOIN FETCH d.product
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
}
