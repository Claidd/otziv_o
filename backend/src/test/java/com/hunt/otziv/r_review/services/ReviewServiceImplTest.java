package com.hunt.otziv.r_review.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.board.ReviewBoardQueryService;
import com.hunt.otziv.r_review.bot.ReviewBotChangeService;
import com.hunt.otziv.r_review.edit.ReviewEditService;
import com.hunt.otziv.r_review.mapper.ReviewDtoMapper;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.nagul.ReviewNagulService;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    private static final String PHOTO_URL = "https://storage.example/reviews/17-photo.jpg";

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewArchiveRepository reviewArchiveRepository;

    @Mock
    private BotService botService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private SubCategoryService subCategoryService;

    @Mock
    private OrderDetailsService orderDetailsService;

    @Mock
    private WorkerService workerService;

    @Mock
    private ManagerService managerService;

    @Mock
    private UserService userService;

    @Mock
    private ProductService productService;

    @Mock
    private FilialService filialService;

    @Mock
    private ReviewDtoMapper reviewDtoMapper;

    @Mock
    private ReviewBoardQueryService reviewBoardQueryService;

    @Mock
    private ReviewNagulService reviewNagulService;

    @Mock
    private ReviewBotChangeService reviewBotChangeService;

    @Mock
    private ReviewEditService reviewEditService;

    @Mock
    private OrderStatusCheckerService orderStatusCheckerService;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    @Test
    void updateReviewPhotoSavesUrlWithoutDuplicateTextCheck() {
        Review review = new Review();
        review.setId(17L);
        review.setText("Уже существующий текст");

        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));
        when(reviewRepository.save(review)).thenReturn(review);

        Review updated = reviewService.updateReviewPhoto(17L, PHOTO_URL);

        assertSame(review, updated);
        assertEquals(PHOTO_URL, review.getUrl());
        verify(reviewRepository).save(review);
        verify(reviewRepository, never()).existsByText(review.getText());
    }

    @Test
    void updateReviewPhotoThrowsWhenReviewMissing() {
        when(reviewRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> reviewService.updateReviewPhoto(404L, PHOTO_URL));
        verify(reviewRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateReviewAllowsWorkerToUnsetVigul() {
        Review review = reviewForVigulUpdate(true);
        ReviewDTO dto = reviewDtoForVigulUpdate(false);

        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));

        reviewService.updateReview("ROLE_WORKER", dto, 17L);

        assertEquals(false, review.isVigul());
        verify(reviewRepository).save(review);
    }

    @Test
    void updateReviewDoesNotAllowWorkerToSetVigul() {
        Review review = reviewForVigulUpdate(false);
        ReviewDTO dto = reviewDtoForVigulUpdate(true);

        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));

        reviewService.updateReview("ROLE_WORKER", dto, 17L);

        assertEquals(false, review.isVigul());
        verify(reviewRepository, never()).save(review);
    }

    @Test
    void updateReviewSynchronizesOrderCounterWhenPublishFlagChanges() {
        Order order = new Order();
        order.setId(91L);
        order.setCounter(0);

        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        Review review = reviewForVigulUpdate(false);
        review.setOrderDetails(details);

        ReviewDTO dto = reviewDtoForVigulUpdate(false);
        dto.setPublish(true);

        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));
        when(reviewRepository.countPublishedByOrderId(91L)).thenReturn(2);

        reviewService.updateReview("ROLE_ADMIN", dto, 17L);

        verify(reviewRepository).save(review);
        verify(orderStatusCheckerService).validateCounterConsistency(order, 2);
    }

    @Test
    void updateOrderDetailAndReviewAndPublishDateSkipsSaturdaysAndDuplicateDates() {
        UUID detailsId = UUID.randomUUID();
        int totalReviews = 35;
        List<Review> reviews = new ArrayList<>();
        List<ReviewDTO> reviewDtos = new ArrayList<>();

        Bot bot = new Bot();
        bot.setCounter(3);

        for (int i = 0; i < totalReviews; i++) {
            long reviewId = i + 1L;
            Review review = new Review();
            review.setId(reviewId);
            review.setText("Готовый текст отзыва " + reviewId);
            review.setBot(i == 0 ? bot : null);
            reviews.add(review);
            reviewDtos.add(ReviewDTO.builder()
                    .id(reviewId)
                    .text("Готовый текст отзыва " + reviewId)
                    .build());
            when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        }

        OrderDetails orderDetails = new OrderDetails();
        orderDetails.setId(detailsId);
        orderDetails.setReviews(reviews);
        orderDetails.setComment("Комментарий");

        OrderDetailsDTO orderDetailsDTO = OrderDetailsDTO.builder()
                .id(detailsId)
                .reviews(reviewDtos)
                .comment("Комментарий")
                .build();

        when(orderDetailsService.getOrderDetailById(detailsId)).thenReturn(orderDetails);

        boolean updated = reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetailsDTO);

        assertTrue(updated);
        Set<LocalDate> uniqueDates = new HashSet<>();

        for (Review review : reviews) {
            LocalDate publishedDate = review.getPublishedDate();
            assertTrue(uniqueDates.add(publishedDate), "Дата публикации не должна повторяться: " + publishedDate);
            assertNotEquals(DayOfWeek.SATURDAY, publishedDate.getDayOfWeek(), "Дата не должна выпадать на субботу");
        }
    }

    private Review reviewForVigulUpdate(boolean vigul) {
        Review review = new Review();
        review.setId(17L);
        review.setText("Текст отзыва");
        review.setAnswer("");
        review.setUrl("");
        review.setVigul(vigul);
        return review;
    }

    private ReviewDTO reviewDtoForVigulUpdate(boolean vigul) {
        return ReviewDTO.builder()
                .id(17L)
                .text("Текст отзыва")
                .answer("")
                .url("")
                .vigul(vigul)
                .build();
    }
}
