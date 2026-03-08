package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public interface ReviewRepository extends CrudRepository<Review, Long> {

    @Query("""
        SELECT new com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO(
            c.id,
            c.title,
            CAST(COUNT(r) AS long),
            CAST(SUM(CASE
                WHEN (r.orderDetails IS NULL
                      OR r.orderDetails.order IS NULL
                      OR r.orderDetails.order.status IS NULL
                      OR r.orderDetails.order.status.title != 'Архив')
                THEN 1
                ELSE 0
            END) AS long),
            CAST((SELECT COUNT(b) FROM Bot b WHERE b.botCity = c AND b.active = true) AS integer)
        )
        FROM City c
        LEFT JOIN c.filial f
        LEFT JOIN Review r ON r.filial = f AND r.publish = false
        GROUP BY c.id, c.title
        HAVING COUNT(r) > 0
        ORDER BY c.title
    """)
    List<CityWithUnpublishedReviewsDTO> findCitiesWithUnpublishedReviewCount();

    @Query("""
        SELECT new com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO(
            c.id,
            c.title,
            CAST(COUNT(r) AS long),
            CAST(SUM(CASE
                WHEN (r.orderDetails IS NULL
                      OR r.orderDetails.order IS NULL
                      OR r.orderDetails.order.status IS NULL
                      OR r.orderDetails.order.status.title != 'Архив')
                THEN 1
                ELSE 0
            END) AS long),
            CAST((SELECT COUNT(b) FROM Bot b WHERE b.botCity = c AND b.active = true) AS integer)
        )
        FROM City c
        LEFT JOIN c.filial f
        LEFT JOIN Review r ON r.filial = f AND r.publish = false
        GROUP BY c.id, c.title
        HAVING COUNT(r) > 0
    """)
    List<CityWithUnpublishedReviewsDTO> findAllWithStats();

    @Query("""
        SELECT new com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO(
            c.id,
            c.title,
            CAST(COUNT(r) AS long),
            CAST(SUM(CASE
                WHEN (r.orderDetails IS NULL
                      OR r.orderDetails.order IS NULL
                      OR r.orderDetails.order.status IS NULL
                      OR r.orderDetails.order.status.title != 'Архив')
                THEN 1
                ELSE 0
            END) AS long),
            CAST((SELECT COUNT(b) FROM Bot b WHERE b.botCity = c AND b.active = true) AS integer)
        )
        FROM City c
        LEFT JOIN c.filial f
        LEFT JOIN Review r ON r.filial = f AND r.publish = false
        WHERE LOWER(c.title) LIKE LOWER(CONCAT('%', :search, '%'))
        GROUP BY c.id, c.title
        HAVING COUNT(r) > 0
    """)
    List<CityWithUnpublishedReviewsDTO> findAllWithStatsBySearch(@Param("search") String search);

    List<Review> findAllByOrderDetailsId(UUID orderDetailsId);

    @Query("SELECT r.id FROM Review r WHERE r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByPublishedDateAndPublish(@Param("localDate") LocalDate localDate);

    @Query("SELECT r.id FROM Review r WHERE r.worker = :worker AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByWorkerAndPublishedDateAndPublish(@Param("worker") Worker worker,
                                                         @Param("localDate") LocalDate localDate);

    @Query("SELECT r.id FROM Review r WHERE r.worker IN :workers AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByManagersAndPublishedDateAndPublish(@Param("workers") Set<Worker> workers,
                                                           @Param("localDate") LocalDate localDate);

    @Query("SELECT r.id FROM Review r WHERE r.worker IN :workers AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByOwnersAndPublishedDateAndPublish(@Param("workers") Set<Worker> workers,
                                                         @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT DISTINCT r
        FROM Review r
        LEFT JOIN FETCH r.product
        LEFT JOIN FETCH r.category
        LEFT JOIN FETCH r.subCategory
        LEFT JOIN FETCH r.bot
        LEFT JOIN FETCH r.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH r.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH r.orderDetails d
        LEFT JOIN FETCH d.product p
        LEFT JOIN FETCH p.productCategory
        LEFT JOIN FETCH d.order o
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.manager om
        LEFT JOIN FETCH om.user
        WHERE r.id IN :reviewId
        ORDER BY r.changed
    """)
    List<Review> findAll(@Param("reviewId") List<Long> reviewId);

    @Query("""
        SELECT DISTINCT r
        FROM Review r
        LEFT JOIN FETCH r.category
        LEFT JOIN FETCH r.subCategory
        LEFT JOIN FETCH r.bot
        LEFT JOIN FETCH r.filial f
        LEFT JOIN FETCH f.city
        LEFT JOIN FETCH r.worker w
        LEFT JOIN FETCH w.user
        LEFT JOIN FETCH r.orderDetails d
        LEFT JOIN FETCH d.product p
        LEFT JOIN FETCH p.productCategory
        LEFT JOIN FETCH d.order o
        LEFT JOIN FETCH o.company
        LEFT JOIN FETCH o.status
        LEFT JOIN FETCH o.manager om
        LEFT JOIN FETCH om.user
        WHERE r.orderDetails.order.id = :orderId
    """)
    List<Review> getAllByOrderId(@Param("orderId") Long orderId);

    @Query("SELECT r.id FROM Review r WHERE r.worker.id = :workerId")
    List<Long> findAllIdByWorkerId(@Param("workerId") Long workerId);

    @Query("select COUNT(r) from Review r where r.publishedDate <= :localDate AND r.worker = :worker AND r.publish = false")
    int findAllByReviewsListStatus(@Param("localDate") LocalDate localDate,
                                   @Param("worker") Worker worker);

    @Query("SELECT r FROM Review r WHERE r.publish = false AND r.bot IS NOT NULL")
    List<Review> findByPublishFalseAndBotIsNotNull();

    @Query("""
        SELECT DISTINCT r
        FROM Review r
        WHERE r.publish = false
          AND r.bot IS NOT NULL
          AND r.bot.active = true
          AND r.filial.id IN :filialIds
    """)
    List<Review> findByPublishFalseAndBotIsNotNullAndFilialIdIn(@Param("filialIds") List<Long> filialIds);

    Page<Review> findByWorkerAndPublishedDateAndPublishFalse(Worker worker, LocalDate publishedDate, Pageable pageable);

    @Query("""
        SELECT r
        FROM Review r
        WHERE r.worker = :worker
          AND r.publishedDate = :publishedDate
          AND r.publish = false
          AND r.vigul = false
          AND (r.bot IS NULL OR r.bot.counter <= 2)
    """)
    Page<Review> findReviewsForWorkerVigul(@Param("worker") Worker worker,
                                           @Param("publishedDate") LocalDate publishedDate,
                                           Pageable pageable);

    @Query("SELECT r FROM Review r WHERE r.bot = :bot AND r.publish = false")
    List<Review> findByBotAndPublishFalse(@Param("bot") Bot bot);

    @Modifying
    @Query("DELETE FROM Review r WHERE r.id IN :reviewIds")
    int deleteByIdIn(@Param("reviewIds") List<Long> reviewIds);

    @Modifying
    @Query("DELETE FROM Review r WHERE r.id = :reviewId")
    void deleteReviewByReviewId(@Param("reviewId") Long reviewId);

    boolean existsByText(String text);

    @Query("SELECT r FROM Review r WHERE r.filial = :filial")
    List<Review> findAllByFilial(@Param("filial") Filial filial);

    @Query("SELECT r FROM Review r WHERE r.filial IN :filials")
    List<Review> findAllByFilials(@Param("filials") Set<Filial> filials);

    @Query("SELECT COUNT(r.id) FROM Review r WHERE r.worker = :worker AND r.publishedDate <= :localDate AND r.publish = false")
    int countByWorkerAndStatusPublish(@Param("worker") Worker worker,
                                      @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT r.worker.id, COUNT(r.id)
        FROM Review r
        WHERE r.worker.id IN :workerIds
          AND r.publishedDate <= :localDate
          AND r.publish = false
        GROUP BY r.worker.id
    """)
    List<Object[]> countByWorkerIdsAndStatusPublish(@Param("workerIds") Collection<Long> workerIds,
                                                    @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT r.worker.id, COUNT(r.id)
        FROM Review r
        WHERE r.worker.id IN :workerIds
          AND r.publishedDate <= :localDate
          AND r.publish = false
          AND r.vigul = false
          AND (r.bot IS NULL OR r.bot.counter <= 2)
        GROUP BY r.worker.id
    """)
    List<Object[]> countByWorkerIdsAndStatusVigul(@Param("workerIds") Collection<Long> workerIds,
                                                  @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT CASE WHEN COUNT(r) > 0 THEN TRUE ELSE FALSE END
        FROM Review r
        WHERE r.worker = :worker
          AND r.publishedDate <= :date
          AND r.publish = false
          AND r.vigul = false
          AND (r.bot IS NULL OR r.bot.counter <= 2)
    """)
    boolean existsActiveNagulReviews(@Param("worker") Worker worker,
                                     @Param("date") LocalDate date);

    @Query("""
        SELECT
            u.fio AS fio,
            COUNT(CASE WHEN r.publishedDate BETWEEN :firstDayOfMonth AND :localDate THEN 1 ELSE NULL END) AS totalReviews,
            COUNT(CASE WHEN r.publishedDate BETWEEN :firstDayOfMonth AND :localDate2 AND r.vigul = false AND r.bot.counter <= 2 THEN 1 ELSE NULL END) AS vigulCount
        FROM Review r
        LEFT JOIN r.worker w
        LEFT JOIN w.user u
        WHERE r.publish = false
          AND u.fio IS NOT NULL
        GROUP BY u.fio

        UNION ALL

        SELECT
            mu.fio AS fio,
            COUNT(CASE WHEN r.publishedDate BETWEEN :firstDayOfMonth AND :localDate THEN 1 ELSE NULL END) AS totalReviews,
            COUNT(CASE WHEN r.publishedDate BETWEEN :firstDayOfMonth AND :localDate2 AND r.vigul = false AND r.bot.counter <= 2 THEN 1 ELSE NULL END) AS vigulCount
        FROM Review r
        LEFT JOIN r.orderDetails od
        LEFT JOIN od.order o
        LEFT JOIN o.manager m
        LEFT JOIN m.user mu
        WHERE r.publish = false
          AND mu.fio IS NOT NULL
        GROUP BY mu.fio
    """)
    List<Object[]> findAllByPublishAndVigul(@Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                            @Param("localDate") LocalDate localDate,
                                            @Param("localDate2") LocalDate localDate2);

    @Query("""
        SELECT
            u.fio AS workerFio,
            COUNT(r.id) AS workerReviewCount,
            m_user.fio AS managerFio,
            COUNT(r.id) AS managerReviewCount
        FROM Review r
        LEFT JOIN r.worker w
        LEFT JOIN r.orderDetails.order.manager m
        LEFT JOIN w.user u
        LEFT JOIN m.user m_user
        WHERE r.publishedDate BETWEEN :firstDayOfMonth AND :lastDayOfMonth
          AND r.publish = true
        GROUP BY u.fio, m_user.fio
    """)
    List<Object[]> getAllReviewsToMonth(@Param("firstDayOfMonth") LocalDate firstDayOfMonth,
                                        @Param("lastDayOfMonth") LocalDate lastDayOfMonth);

    @Query("""
        SELECT r.worker.id, COUNT(r.id)
        FROM Review r
        WHERE r.worker.id IN :workerIds
          AND r.publishedDate <= :localDate
          AND r.publish = false
        GROUP BY r.worker.id
    """)
    List<Object[]> countByWorkerIdsAndStatusPublish(@Param("workerIds") List<Long> workerIds,
                                                    @Param("localDate") LocalDate localDate);

    @Query("""
        SELECT r.worker.id, COUNT(r.id)
        FROM Review r
        LEFT JOIN r.bot b
        WHERE r.worker.id IN :workerIds
          AND r.publishedDate <= :localDate
          AND r.publish = false
          AND r.vigul = false
          AND (b IS NULL OR b.counter <= 2)
        GROUP BY r.worker.id
    """)
    List<Object[]> countByWorkerIdsAndStatusVigul(@Param("workerIds") List<Long> workerIds,
                                                  @Param("localDate") LocalDate localDate);

    @Query("""
       select count(r)
       from Review r
       join r.worker w
       join w.user u
       where u.id = :userId
       """)
    int countReviewsForWorkerUserId(@Param("userId") Long userId);
}
