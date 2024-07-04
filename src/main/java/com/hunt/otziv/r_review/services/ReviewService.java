package com.hunt.otziv.r_review.services;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import org.springframework.data.domain.Page;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ReviewService {

    Review save(Review review);
    boolean deleteReview(Long reviewId);
    List<ReviewDTOOne> getReviewsAllByOrderId(Long orderId);
    Page<ReviewDTOOne> getAllReviewDTOAndDateToAdmin(int pageNumber, int pageSize);
    Page<ReviewDTOOne> getAllReviewDTOByWorkerByPublish(Principal principal, int pageNumber, int pageSize);
    Page<ReviewDTOOne> getAllReviewDTOByManagerByPublish(Principal principal, int pageNumber, int pageSize);
    Page<ReviewDTOOne> getAllReviewDTOByOwnerByPublish(Principal principal, int pageNumber, int pageSize);
    void changeBot(Long id);
    void deActivateAndChangeBot(Long reviewId, Long botId);
    ReviewDTO getReviewDTOById(Long reviewId);
    Review getReviewById(Long reviewId);
    void updateReview(ReviewDTO reviewDTO, Long reviewId);
    void updateOrderDetailAndReview(OrderDetailsDTO orderDetailsDTO, ReviewDTO reviewDTO, Long reviewId);
    boolean updateOrderDetailAndReviewAndPublishDate(OrderDetailsDTO orderDetailsDTO);
    List<Review> getReviewsAllByOrderDetailsId(UUID orderDetailsId);
    List<Long> getReviewByWorkerId(Long workerId);
    List<Review> findAllByListId(List<Long> reviewId);
    int findAllByReviewListStatus(String username);
    List<Review> getAllWorkerReviews(Long workerId);

    void deleteReviewsByOrderId(Long orderId);

    List<Review> findAllByFilial(Filial filial);

    void updateReviewByFilials(Set<Filial> filials, Long categoryId, Long subCategoryId);
}
