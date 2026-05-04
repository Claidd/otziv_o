package com.hunt.otziv.p_products.utils;

import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.r_review.model.Review;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getAllReviews;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getFirstDetail;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getFirstReview;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.hasDetails;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeFilialTitle;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderReviewGraphTest {

    @Test
    void returnsNullAndEmptyDefaultsForMissingGraph() {
        assertNull(getFirstDetail(null));
        assertNull(getFirstReview(null));
        assertEquals(List.of(), getAllReviews(null));
        assertFalse(hasDetails(null));
        assertEquals("", safeStatusTitle(null));
        assertEquals("Без филиала", safeFilialTitle(null));
        assertEquals("", safeString(null));
    }

    @Test
    void traversesDetailsAndReviewsInOrder() {
        Review firstReview = Review.builder().id(1L).build();
        Review secondReview = Review.builder().id(2L).build();
        OrderDetails firstDetail = OrderDetails.builder()
                .reviews(List.of(firstReview))
                .build();
        OrderDetails secondDetail = OrderDetails.builder()
                .reviews(List.of(secondReview))
                .build();
        Order order = Order.builder()
                .details(List.of(firstDetail, secondDetail))
                .build();

        assertSame(firstDetail, getFirstDetail(order));
        assertSame(firstReview, getFirstReview(order));
        assertEquals(List.of(firstReview, secondReview), getAllReviews(order));
        assertTrue(hasDetails(order));
    }

    @Test
    void readsSafeTitles() {
        Order order = Order.builder()
                .status(OrderStatus.builder().title("Публикация").build())
                .filial(Filial.builder().title("Центр").build())
                .build();

        assertEquals("Публикация", safeStatusTitle(order));
        assertEquals("Центр", safeFilialTitle(order));
        assertEquals("", safeString(null));
        assertEquals("text", safeString("text"));
    }
}
