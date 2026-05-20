package com.hunt.otziv.review_recovery.repository;

import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ReviewRecoveryTaskRepository extends JpaRepository<ReviewRecoveryTask, Long> {

    @Query("""
        SELECT COUNT(t.id)
        FROM ReviewRecoveryTask t
        WHERE t.sourceReview.id = :reviewId
          AND t.status IN :taskStatuses
          AND t.batch.status IN :batchStatuses
    """)
    long countActiveTasksForReview(
            @Param("reviewId") Long reviewId,
            @Param("taskStatuses") Collection<ReviewRecoveryTaskStatus> taskStatuses,
            @Param("batchStatuses") Collection<ReviewRecoveryBatchStatus> batchStatuses
    );

    @Query("""
        SELECT MAX(t.scheduledDate)
        FROM ReviewRecoveryTask t
        WHERE t.batch.id = :batchId
          AND t.status <> :excludedStatus
    """)
    LocalDate maxScheduledDateByBatchId(
            @Param("batchId") Long batchId,
            @Param("excludedStatus") ReviewRecoveryTaskStatus excludedStatus
    );

    long countByBatchIdAndStatus(Long batchId, ReviewRecoveryTaskStatus status);

    @Query("""
        SELECT t
        FROM ReviewRecoveryTask t
        JOIN FETCH t.batch b
        JOIN FETCH t.order o
        JOIN FETCH t.sourceReview r
        LEFT JOIN FETCH r.bot rb
        LEFT JOIN FETCH t.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH t.bot bot
        WHERE o.id = :orderId
          AND b.status IN :batchStatuses
        ORDER BY t.scheduledDate ASC, t.id ASC
    """)
    List<ReviewRecoveryTask> findByOrderIdAndBatchStatusIn(
            @Param("orderId") Long orderId,
            @Param("batchStatuses") Collection<ReviewRecoveryBatchStatus> batchStatuses
    );

    boolean existsByIdAndOrderId(Long id, Long orderId);

    @Query(
            value = """
                SELECT DISTINCT t
                FROM ReviewRecoveryTask t
                JOIN FETCH t.batch batch
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
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
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
                FROM ReviewRecoveryTask t
                JOIN t.batch batch
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<ReviewRecoveryTask> findDueTasksToAdmin(
            @Param("status") ReviewRecoveryTaskStatus status,
            @Param("batchStatus") ReviewRecoveryBatchStatus batchStatus,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT DISTINCT t
                FROM ReviewRecoveryTask t
                JOIN FETCH t.batch batch
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
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND o.manager IN :managers
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
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
                FROM ReviewRecoveryTask t
                JOIN t.batch batch
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND o.manager IN :managers
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<ReviewRecoveryTask> findDueTasksToOwner(
            @Param("managers") Collection<Manager> managers,
            @Param("status") ReviewRecoveryTaskStatus status,
            @Param("batchStatus") ReviewRecoveryBatchStatus batchStatus,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT DISTINCT t
                FROM ReviewRecoveryTask t
                JOIN FETCH t.batch batch
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
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND o.manager = :manager
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
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
                FROM ReviewRecoveryTask t
                JOIN t.batch batch
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND o.manager = :manager
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<ReviewRecoveryTask> findDueTasksToManager(
            @Param("manager") Manager manager,
            @Param("status") ReviewRecoveryTaskStatus status,
            @Param("batchStatus") ReviewRecoveryBatchStatus batchStatus,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query(
            value = """
                SELECT DISTINCT t
                FROM ReviewRecoveryTask t
                JOIN FETCH t.batch batch
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
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND t.worker = :worker
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
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
                FROM ReviewRecoveryTask t
                JOIN t.batch batch
                JOIN t.order o
                LEFT JOIN o.company c
                LEFT JOIN t.sourceReview r
                LEFT JOIN r.filial f
                LEFT JOIN f.city city
                LEFT JOIN t.worker w
                LEFT JOIN t.bot b
                WHERE t.status = :status
                  AND batch.status = :batchStatus
                  AND t.scheduledDate <= :date
                  AND t.worker = :worker
                  AND (
                    LOWER(COALESCE(c.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(t.recoveryText, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.text, '')) LIKE :keyword
                    OR LOWER(COALESCE(r.answer, '')) LIKE :keyword
                    OR LOWER(COALESCE(f.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(city.title, '')) LIKE :keyword
                    OR LOWER(COALESCE(b.fio, '')) LIKE :keyword
                    OR LOWER(COALESCE(w.user.fio, '')) LIKE :keyword
                  )
            """
    )
    Page<ReviewRecoveryTask> findDueTasksToWorker(
            @Param("worker") Worker worker,
            @Param("status") ReviewRecoveryTaskStatus status,
            @Param("batchStatus") ReviewRecoveryBatchStatus batchStatus,
            @Param("date") LocalDate date,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    long countByStatusAndBatchStatusAndScheduledDateLessThanEqual(
            ReviewRecoveryTaskStatus status,
            ReviewRecoveryBatchStatus batchStatus,
            LocalDate scheduledDate
    );

    long countByStatusAndBatchStatusAndScheduledDateLessThanEqualAndWorker(
            ReviewRecoveryTaskStatus status,
            ReviewRecoveryBatchStatus batchStatus,
            LocalDate scheduledDate,
            Worker worker
    );

    long countByStatusAndBatchStatusAndScheduledDateLessThanEqualAndOrderManager(
            ReviewRecoveryTaskStatus status,
            ReviewRecoveryBatchStatus batchStatus,
            LocalDate scheduledDate,
            Manager manager
    );

    long countByStatusAndBatchStatusAndScheduledDateLessThanEqualAndOrderManagerIn(
            ReviewRecoveryTaskStatus status,
            ReviewRecoveryBatchStatus batchStatus,
            LocalDate scheduledDate,
            Collection<Manager> managers
    );
}
