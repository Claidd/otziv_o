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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    // Метод для получения всех данных с сортировкой (без пагинации)
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

    // Метод с поиском и сортировкой
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
//
//    @Query("""
//    SELECT new com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO(
//        c.id,
//        c.title,
//        CAST(COUNT(r) AS long),
//        CAST(SUM(CASE
//            WHEN (r.orderDetails IS NULL
//                  OR r.orderDetails.order IS NULL
//                  OR r.orderDetails.order.status IS NULL
//                  OR r.orderDetails.order.status.title != 'Архив')
//            THEN 1
//            ELSE 0
//        END) AS long),
//        CAST((SELECT COUNT(b) FROM Bot b WHERE b.botCity = c AND b.active = true) AS integer)
//    )
//    FROM City c
//    LEFT JOIN c.filial f
//    LEFT JOIN Review r ON r.filial = f AND r.publish = false
//    GROUP BY c.id, c.title
//    HAVING COUNT(r) > 0
//    ORDER BY c.title
//""")
//    List<CityWithUnpublishedReviewsDTO> findCitiesWithUnpublishedReviewCount();

    List<Review> findAllByOrderDetailsId(UUID orderDetailsId);

    @Query("SELECT r.id FROM Review r WHERE r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByPublishedDateAndPublish(LocalDate localDate);

    @Query("SELECT r.id FROM Review r WHERE r.worker = :worker AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByWorkerAndPublishedDateAndPublish(Worker worker, LocalDate localDate);


    @Query("SELECT r.id FROM Review r WHERE r.worker IN :workers AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByManagersAndPublishedDateAndPublish(Set<Worker> workers, LocalDate localDate);

    @Query("SELECT r.id FROM Review r WHERE r.worker IN :workers AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByOwnersAndPublishedDateAndPublish(Set<Worker> workers, LocalDate localDate);

    @Query("SELECT r FROM Review r LEFT JOIN FETCH r.product LEFT JOIN FETCH r.category LEFT JOIN FETCH r.subCategory LEFT JOIN FETCH r.bot LEFT JOIN FETCH r.filial LEFT JOIN FETCH r.worker w LEFT JOIN FETCH w.user LEFT JOIN FETCH r.orderDetails d LEFT JOIN FETCH d.product p LEFT JOIN FETCH p.productCategory LEFT JOIN FETCH d.order o LEFT JOIN FETCH o.company WHERE r.id IN :reviewId  ORDER BY r.changed")
    List<Review> findAll(List<Long> reviewId);

    // ИЗМЕНЕНИЕ 6: Метод для поиска всех активных отзывов с ботами
//    @Query("SELECT r FROM Review r WHERE r.publish = false AND r.bot IS NOT NULL")
//    List<Review> findByPublishFalseAndBotIsNotNull();
//
//    @Query("SELECT r FROM Review r WHERE r.bot = :bot AND r.publish = false")
//    List<Review> findByBotAndPublishFalse(@Param("bot") Bot bot);

    @Query("SELECT r FROM Review r LEFT JOIN FETCH r.category LEFT JOIN FETCH r.subCategory LEFT JOIN FETCH r.bot LEFT JOIN FETCH r.filial LEFT JOIN FETCH r.worker w LEFT JOIN FETCH w.user LEFT JOIN FETCH r.orderDetails d LEFT JOIN FETCH d.product p LEFT JOIN FETCH p.productCategory LEFT JOIN FETCH d.order o LEFT JOIN FETCH o.company WHERE r.orderDetails.order.id = :orderId")
    List<Review> getAllByOrderId(Long orderId);


    @Query("SELECT r.id FROM Review r WHERE r.worker.id = :workerId")
    List<Long> findAllIdByWorkerId(Long workerId);

    @Query("select COUNT(r) from Review r where r.publishedDate <= :localDate AND r.worker = :worker AND r.publish = false")
    int findAllByReviewsListStatus(LocalDate localDate, Worker worker);


    @Query("SELECT r FROM Review r WHERE r.publish = false AND r.bot IS NOT NULL")
    List<Review> findByPublishFalseAndBotIsNotNull();

//    @Query("SELECT r FROM Review r WHERE r.publish = false AND r.bot IS NOT NULL AND r.filial.id IN :filialIds")
//    List<Review> findByPublishFalseAndBotIsNotNullAndFilialIdIn(@Param("filialIds") List<Long> filialIds);

    @Query("""
    SELECT DISTINCT r FROM Review r 
    WHERE r.publish = false 
    AND r.bot IS NOT NULL 
    AND r.bot.active = true 
    AND r.filial.id IN :filialIds
    """)
    List<Review> findByPublishFalseAndBotIsNotNullAndFilialIdIn(
            @Param("filialIds") List<Long> filialIds);



    // Метод с пагинацией на уровне БД
    Page<Review> findByWorkerAndPublishedDateAndPublishFalse(
            Worker worker,
            LocalDate publishedDate,
            Pageable pageable);

    // Добавьте этот метод для пагинации выгула работника
    @Query("SELECT r FROM Review r WHERE " +
            "r.worker = :worker AND " +
            "r.publishedDate = :publishedDate AND " +
            "r.publish = false AND " +
            "r.vigul = false AND " +
            "(r.bot IS NULL OR r.bot.counter <= 2)")
    Page<Review> findReviewsForWorkerVigul(
            @Param("worker") Worker worker,
            @Param("publishedDate") LocalDate publishedDate,
            Pageable pageable);

    @Query("SELECT r FROM Review r WHERE r.bot = :bot AND r.publish = false")
    List<Review> findByBotAndPublishFalse(@Param("bot") Bot bot);

    @Modifying
    @Query("DELETE FROM Review r WHERE r.id IN :reviewIds")
    int deleteByIdIn(@Param("reviewIds") List<Long> reviewIds);

    @Modifying
    @Query("DELETE FROM Review r WHERE r.id = :reviewId")
    void deleteReviewByReviewId(Long reviewId);

    // Проверка наличия записи с таким же текстом
    boolean existsByText(String text);

    @Query("SELECT r FROM Review r WHERE r.filial = :filial")
    List<Review> findAllByFilial(@Param("filial") Filial filial);

    @Query("SELECT r FROM Review r WHERE r.filial IN :filials")
    List<Review> findAllByFilials(Set<Filial> filials);

    @Query("SELECT COUNT(r.id) FROM Review r WHERE r.worker = :worker AND r.publishedDate <= :localDate AND r.publish = false")
    int countByWorkerAndStatusPublish(Worker worker, LocalDate localDate);


    // В ReviewRepository.java
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN TRUE ELSE FALSE END " +
            "FROM Review r " +
            "WHERE r.worker = :worker " +
            "AND r.publishedDate <= :date " +
            "AND r.publish = false " +
            "AND r.vigul = false " +
            "AND (r.bot IS NULL OR r.bot.counter <= 2)")
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
    List<Object[]> findAllByPublishAndVigul(LocalDate firstDayOfMonth, LocalDate localDate, LocalDate localDate2);



    @Query("""
    SELECT 
        u.fio AS workerFio, 
        COUNT( r.id) AS workerReviewCount, 
        m_user.fio AS managerFio, 
        COUNT( r.id) AS managerReviewCount
    FROM Review r 
    LEFT JOIN r.worker w 
    LEFT JOIN r.orderDetails.order.manager m 
    LEFT JOIN w.user u 
    LEFT JOIN m.user m_user 
    WHERE r.publishedDate BETWEEN :firstDayOfMonth AND :lastDayOfMonth 
      AND r.publish = true 
    GROUP BY u.fio, m_user.fio
""")
    List<Object[]> getAllReviewsToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);



//    // Опционально: статистика по всем городам (включая те, где нет отзывов)
//    @Query("""
//        SELECT new com.hunt.otziv.r_review.dto.CityWithUnpublishedReviewsDTO(
//            c.id,
//            c.title,
//            COUNT(CASE WHEN r.publish = false THEN 1 END)
//        )
//        FROM City c
//        LEFT JOIN c.filial f
//        LEFT JOIN Review r ON r.filial = f
//        GROUP BY c.id, c.title
//        ORDER BY c.title
//    """)
//    List<CityWithUnpublishedReviewsDTO> findAllCitiesWithReviewCount();

//    @Query("""
//    SELECT
//        u.fio,
//        COUNT(r.id),
//        SUM(CASE WHEN r.vigul = false AND r.bot.counter < 2 THEN 1 ELSE 0 END)
//    FROM Review r
//    LEFT JOIN r.worker w
//    LEFT JOIN w.user u
//    LEFT JOIN r.orderDetails od
//    LEFT JOIN od.order o
//    LEFT JOIN o.manager m
//    LEFT JOIN m.user mu
//    WHERE r.publishedDate BETWEEN :firstDayOfMonth AND :localDate
//      AND r.publish = false
//    GROUP BY u.fio
//""")
//    List<Object[]> findAllByPublishAndVigul(LocalDate firstDayOfMonth, LocalDate localDate);


//    @Query("""
//    SELECT
//        u.fio AS workerFio,
//        COUNT(DISTINCT r.id) AS workerReviewCount,
//        m_user.fio AS managerFio,
//        COUNT(DISTINCT r.id) AS managerReviewCount
//    FROM Review r
//    LEFT JOIN r.worker w
//    LEFT JOIN r.orderDetails.order.manager m
//    LEFT JOIN w.user u
//    LEFT JOIN m.user m_user
//    WHERE r.publishedDate BETWEEN :firstDayOfMonth AND :lastDayOfMonth
//      AND r.publish = true
//    GROUP BY u.fio, m_user.fio
//""")
//    List<Object[]> getAllReviewsToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);



}
