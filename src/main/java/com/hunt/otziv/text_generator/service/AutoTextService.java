package com.hunt.otziv.text_generator.service;

import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.r_review.model.Review;

import java.util.List;

public interface AutoTextService {
    List<Review> toEntityListReviewsFromDTO(OrderDTO orderDTO, OrderDetails savedOrderDetails);
    boolean changeReviewText(Long reviewId);
}
