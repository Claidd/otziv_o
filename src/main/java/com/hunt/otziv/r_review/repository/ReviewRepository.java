package com.hunt.otziv.r_review.repository;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ReviewRepository extends CrudRepository<Review, Long> {

    List<Review> findAllByOrderDetailsId(UUID orderDetailsId);

    @Query("SELECT r.id FROM Review r WHERE r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByPublishedDateAndPublish(LocalDate localDate);

    @Query("SELECT r.id FROM Review r WHERE r.worker = :worker AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByWorkerAndPublishedDateAndPublish(Worker worker, LocalDate localDate);


    @Query("SELECT r.id FROM Review r WHERE r.worker IN :workers AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByManagersAndPublishedDateAndPublish(Set<Worker> workers, LocalDate localDate);

    @Query("SELECT r.id FROM Review r WHERE r.worker IN :workers AND r.publishedDate <= :localDate AND r.publish = false")
    List<Long> findAllByOwnersAndPublishedDateAndPublish(Set<Worker> workers, LocalDate localDate);

    @Query("SELECT r FROM Review r LEFT JOIN FETCH r.category LEFT JOIN FETCH r.subCategory LEFT JOIN FETCH r.bot LEFT JOIN FETCH r.filial LEFT JOIN FETCH r.worker w LEFT JOIN FETCH w.user LEFT JOIN FETCH r.orderDetails d LEFT JOIN FETCH d.product p LEFT JOIN FETCH p.productCategory LEFT JOIN FETCH d.order o LEFT JOIN FETCH o.company WHERE r.id IN :reviewId  ORDER BY r.changed")
    List<Review> findAll(List<Long> reviewId);



    @Query("SELECT r FROM Review r LEFT JOIN FETCH r.category LEFT JOIN FETCH r.subCategory LEFT JOIN FETCH r.bot LEFT JOIN FETCH r.filial LEFT JOIN FETCH r.worker w LEFT JOIN FETCH w.user LEFT JOIN FETCH r.orderDetails d LEFT JOIN FETCH d.product p LEFT JOIN FETCH p.productCategory LEFT JOIN FETCH d.order o LEFT JOIN FETCH o.company WHERE r.orderDetails.order.id = :orderId")
    List<Review> getAllByOrderId(Long orderId);


    @Query("SELECT r.id FROM Review r WHERE r.worker.id = :workerId")
    List<Long> findAllIdByWorkerId(Long workerId);

    @Query("select COUNT(r) from Review r where r.publishedDate <= :localDate AND r.worker = :worker AND r.publish = false")
    int findAllByReviewsListStatus(LocalDate localDate, Worker worker);


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



}
