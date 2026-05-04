package com.hunt.otziv.r_review.services;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.NagulResult;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.util.Pair;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ReviewService {

    Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(LocalDate localDate, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByOrderStatusToAdmin(String status, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByOrderStatus(String status, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    List<Review> saveAll(List<Review> reviews);

    Review save(Review review);

    boolean deleteReview(Long reviewId);

    List<Review> getReviewsAllByOrderDetailsId(UUID orderDetailsId);

    List<Long> getReviewByWorkerId(Long workerId);

    List<Review> findAllByListId(List<Long> reviewId);

    List<ReviewDTOOne> getReviewsAllByOrderId(Long orderId);

    ReviewDTOOne toReviewDTOOne(Review review);

    void updateReview(String userRole, ReviewDTO reviewDTO, Long reviewId);

    void deleteReviewsByOrderId(Long reviewId);

    List<Review> findAllByFilial(Filial filial);

    void updateReviewByFilials(Set<Filial> filials, Long categoryId, Long subCategoryId);

    void updateOrderDetailAndReview(OrderDetailsDTO orderDetailsDTO, ReviewDTO reviewDTO, Long reviewId);

    boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO);

    void changeBot(Long reviewId);

    void deActivateAndChangeBot(Long reviewId, Long botId);

    ReviewDTO getReviewDTOById(Long reviewId);

    Review getReviewById(Long reviewId);

    boolean updateReviewText(Long orderId, Long reviewId, String text);

    boolean updateReviewAnswer(Long orderId, Long reviewId, String answer);

    boolean updateReviewNote(Long orderId, Long reviewId, String comment);

    Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOAndDateToAdminToVigul(LocalDate localDate, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByManagerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection);

    Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublishToVigul(LocalDate localDate, java.security.Principal principal, int pageNumber, int pageSize, String sortDirection, String keyword);

    void changeNagulReview(Long reviewId);

    void performNagulWithExceptions(Long reviewId, String username);

    int countOrdersByWorkerAndStatusPublish(Worker worker, LocalDate localDate);

    int countOrdersByWorkerAndStatusVigul(Worker worker, LocalDate localDate);

    Map<String, Pair<Long, Long>> getAllPublishAndVigul(LocalDate firstDayOfMonth, LocalDate localDate);

    Map<String, Long> getAllReviewsToMonth(LocalDate firstDayOfMonth, LocalDate lastDayOfMonth);

    boolean hasActiveNagulReviews(java.security.Principal principal);

    void deleteAllByIdIn(List<Long> reviewIds);

    // ========================= НОВЫЕ BATCH-МЕТОДЫ =========================

    Map<Long, Integer> countOrdersByWorkerIdsAndStatusPublish(List<Long> workerIds, LocalDate localDate);

    Map<Long, Integer> countOrdersByWorkerIdsAndStatusVigul(List<Long> workerIds, LocalDate localDate);

//    int findAllByReviewListStatus(String name);

    int countReviewsForWorkerUserId(Long userId);

    int countBoardReviewsToPublish(LocalDate localDate, Principal principal, String role);

    int countBoardReviewsToVigul(LocalDate localDate, Principal principal, String role);

    int countBoardReviewsByOrderStatus(String status, Principal principal, String role);

    Map<String, Integer> countBoardReviewMetrics(LocalDate publishDate, LocalDate vigulDate, String badStatus, Principal principal, String role);

    int findAllByReviewListStatus(String name);
}
