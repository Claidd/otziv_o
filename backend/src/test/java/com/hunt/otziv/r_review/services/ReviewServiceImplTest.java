package com.hunt.otziv.r_review.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_companies.services.FilialService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.board.service.ReviewBoardQueryService;
import com.hunt.otziv.r_review.bot.service.ReviewBotChangeService;
import com.hunt.otziv.r_review.bot.service.ReviewAccountWalkScheduleService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.edit.service.ReviewEditService;
import com.hunt.otziv.r_review.mapper.ReviewDtoMapper;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.nagul.service.ReviewNagulService;
import com.hunt.otziv.r_review.repository.ReviewArchiveRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.data.util.Pair;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private ReviewAccountWalkScheduleService reviewAccountWalkScheduleService;

    @Mock
    private ReviewEditService reviewEditService;

    @Mock
    private OrderStatusCheckerService orderStatusCheckerService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Mock
    private GamificationEventService gamificationEventService;

    @Mock
    private OrderAggregateMutationLockService orderAggregateMutationLockService;

    @Mock
    private WorkerAssignmentMutationGuardService assignmentMutationGuardService;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    @Test
    void updateOrderDetailAndReviewRejectsReviewFromAnotherOrderBeforeAnyWrite() {
        UUID requestedDetailsId = UUID.randomUUID();
        OrderDetails requestedDetails = new OrderDetails();
        requestedDetails.setId(requestedDetailsId);
        OrderDetails foreignDetails = new OrderDetails();
        foreignDetails.setId(UUID.randomUUID());
        Review review = new Review();
        review.setId(77L);
        review.setOrderDetails(foreignDetails);
        when(reviewRepository.findById(77L)).thenReturn(Optional.of(review));
        when(orderDetailsService.getOrderDetailById(requestedDetailsId)).thenReturn(requestedDetails);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> reviewService.updateOrderDetailAndReview(
                        OrderDetailsDTO.builder().id(requestedDetailsId).comment("tampered").build(),
                        ReviewDTO.builder().id(77L).text("tampered").build(),
                        77L
                )
        );

        assertEquals(400, exception.getStatusCode().value());
        verify(reviewRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(orderDetailsService, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sharedFormBatchUsesOneCanonicalDetailFetchForManyReviews() {
        UUID detailsId = UUID.randomUUID();
        OrderDetails details = new OrderDetails();
        details.setId(detailsId);
        details.setComment("old comment");
        List<Review> reviews = new ArrayList<>();
        List<ReviewDTO> updates = new ArrayList<>();
        for (long id = 1; id <= 50; id++) {
            Review review = new Review();
            review.setId(id);
            review.setText("old " + id);
            review.setAnswer("answer " + id);
            review.setOrderDetails(details);
            reviews.add(review);
            updates.add(ReviewDTO.builder()
                    .id(id)
                    .text("new " + id)
                    .answer("answer " + id)
                    .build());
        }
        details.setReviews(reviews);
        when(orderDetailsService.getOrderDetailById(detailsId)).thenReturn(details);

        reviewService.updateOrderDetailAndReviews(OrderDetailsDTO.builder()
                .id(detailsId)
                .comment("new comment")
                .reviews(updates)
                .build());

        assertEquals("new comment", details.getComment());
        assertEquals("new 1", reviews.getFirst().getText());
        assertEquals("new 50", reviews.getLast().getText());
        verify(orderDetailsService).getOrderDetailById(detailsId);
        verify(orderDetailsService).save(details);
        verify(reviewRepository).saveAll(org.mockito.ArgumentMatchers.any());
        verify(reviewRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sharedFormBatchRejectsForeignReviewBeforeWrites() {
        UUID detailsId = UUID.randomUUID();
        OrderDetails details = new OrderDetails();
        details.setId(detailsId);
        Review ownReview = new Review();
        ownReview.setId(1L);
        ownReview.setOrderDetails(details);
        details.setReviews(List.of(ownReview));
        when(orderDetailsService.getOrderDetailById(detailsId)).thenReturn(details);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> reviewService.updateOrderDetailAndReviews(OrderDetailsDTO.builder()
                        .id(detailsId)
                        .comment("tampered")
                        .reviews(List.of(ReviewDTO.builder().id(2L).text("foreign").build()))
                        .build())
        );

        assertEquals(400, exception.getStatusCode().value());
        verify(orderDetailsService, never()).save(org.mockito.ArgumentMatchers.any());
        verify(reviewRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
        verify(reviewRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

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
    void workerCannotChangePublicationDateWithoutCompanyPermission() {
        Review review = reviewForPublicationDatePermission(false);
        ReviewDTO dto = publicationDateUpdate(review.getPublishedDate().plusDays(7));
        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> reviewService.updateReview("ROLE_WORKER", dto, 17L)
        );

        assertEquals(403, exception.getStatusCode().value());
        assertEquals("Для смены даты публикации обратитесь к менеджеру", exception.getReason());
        verify(reviewRepository, never()).save(review);
    }

    @Test
    void workerCanChangePublicationDateWithCompanyPermission() {
        Review review = reviewForPublicationDatePermission(true);
        LocalDate nextDate = nextAllowedPublicationDate(review.getPublishedDate().plusDays(7));
        ReviewDTO dto = publicationDateUpdate(nextDate);
        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));

        reviewService.updateReview("ROLE_WORKER", dto, 17L);

        assertEquals(nextDate, review.getPublishedDate());
        verify(reviewRepository).save(review);
    }

    @Test
    void managerCanChangePublicationDateWithoutCompanyPermission() {
        Review review = reviewForPublicationDatePermission(false);
        LocalDate nextDate = nextAllowedPublicationDate(review.getPublishedDate().plusDays(7));
        ReviewDTO dto = publicationDateUpdate(nextDate);
        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));

        reviewService.updateReview("ROLE_MANAGER", dto, 17L);

        assertEquals(nextDate, review.getPublishedDate());
        verify(reviewRepository).save(review);
    }

    @Test
    void managerCannotClearUnpublishedDateWhileOrderIsInPublication() {
        Review review = reviewForPublicationDatePermission(false);
        review.getOrderDetails().getOrder().setStatus(OrderStatus.builder().title("Публикация").build());
        LocalDate originalDate = review.getPublishedDate();
        ReviewDTO dto = publicationDateUpdate(null);
        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> reviewService.updateReview("ROLE_MANAGER", dto, 17L)
        );

        assertEquals(409, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("сначала переведите заказ в «Коррекцию»"));
        assertEquals(originalDate, review.getPublishedDate());
        verify(reviewRepository, never()).save(review);
    }

    @Test
    void manualPublicationDateCannotUndercutCurrentAccountWalkWindow() {
        Review review = reviewForPublicationDatePermission(false);
        LocalDate requestedDate = nextAllowedPublicationDate(LocalDate.now().plusDays(3));
        LocalDate walkNotBefore = requestedDate.plusDays(2);
        ReviewDTO dto = publicationDateUpdate(requestedDate);
        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));
        when(reviewAccountWalkScheduleService.minimumPublicationDateForCurrentAccount(review))
                .thenReturn(walkNotBefore);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> reviewService.updateReview("ROLE_MANAGER", dto, 17L)
        );

        assertEquals(400, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains(walkNotBefore.toString()));
        verify(reviewRepository, never()).save(review);
    }

    private Review reviewForPublicationDatePermission(boolean allowed) {
        Company company = new Company();
        company.setAllowWorkerPublicationDateEdit(allowed);
        Order order = new Order();
        order.setCompany(company);
        OrderDetails details = new OrderDetails();
        details.setId(UUID.randomUUID());
        details.setOrder(order);
        Review review = new Review();
        review.setId(17L);
        review.setText("Текст отзыва");
        review.setPublishedDate(nextAllowedPublicationDate(LocalDate.now().plusDays(7)));
        review.setOrderDetails(details);
        return review;
    }

    private ReviewDTO publicationDateUpdate(LocalDate date) {
        return ReviewDTO.builder()
                .id(17L)
                .text("Текст отзыва")
                .publishedDate(date)
                .build();
    }

    private LocalDate nextAllowedPublicationDate(LocalDate date) {
        LocalDate result = date;
        while (result.getDayOfWeek() == DayOfWeek.SATURDAY || result.getDayOfWeek() == DayOfWeek.SUNDAY) {
            result = result.plusDays(1);
        }
        return result;
    }

    @Test
    void getAllPublishAndVigulMergesDuplicateFioRows() {
        LocalDate firstDayOfMonth = LocalDate.of(2026, 5, 1);
        LocalDate localDate = LocalDate.of(2026, 5, 22);
        when(reviewRepository.findAllByPublishAndVigul(firstDayOfMonth, localDate, localDate.plusDays(2)))
                .thenReturn(List.of(
                        new Object[]{"Same User", 10L, 2L},
                        new Object[]{"Same User", 4L, 1L}
                ));

        Map<String, Pair<Long, Long>> result = reviewService.getAllPublishAndVigul(firstDayOfMonth, localDate);

        Pair<Long, Long> counts = result.get("Same User");
        assertEquals(3L, counts.getFirst());
        assertEquals(14L, counts.getSecond());
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
    void updateReviewRejectsPublicationDateTooFarAhead() {
        Review review = reviewForVigulUpdate(false);
        ReviewDTO dto = reviewDtoForVigulUpdate(false);
        dto.setPublishedDate(LocalDate.now().plusDays(91));

        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reviewService.updateReview("ROLE_MANAGER", dto, 17L)
        );

        assertTrue(exception.getMessage().contains("Дата публикации слишком далеко"));
        verify(reviewRepository, never()).save(review);
    }

    @Test
    void updateReviewRejectsPublicationDateMoreThanThirtyDaysAfterPreviousReview() {
        UUID detailsId = UUID.randomUUID();
        OrderDetails details = new OrderDetails();
        details.setId(detailsId);

        Review previous = new Review();
        previous.setId(16L);
        previous.setPublishedDate(LocalDate.now());
        previous.setOrderDetails(details);

        Review review = reviewForVigulUpdate(false);
        review.setOrderDetails(details);

        ReviewDTO dto = reviewDtoForVigulUpdate(false);
        dto.setPublishedDate(LocalDate.now().plusDays(31));

        when(reviewRepository.findById(17L)).thenReturn(Optional.of(review));
        when(reviewRepository.findAllByOrderDetailsId(detailsId)).thenReturn(List.of(previous, review));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reviewService.updateReview("ROLE_MANAGER", dto, 17L)
        );

        assertTrue(exception.getMessage().contains("предыдущего отзыва"));
        verify(reviewRepository, never()).save(review);
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
        verify(reviewRepository).saveAll(org.mockito.ArgumentMatchers.any());
        verify(reviewRepository, never()).findById(org.mockito.ArgumentMatchers.any());
        Set<LocalDate> uniqueDates = new HashSet<>();
        LocalDate earliestDate = reviews.stream()
                .map(Review::getPublishedDate)
                .min(LocalDate::compareTo)
                .orElseThrow();

        assertEquals(earliestDate, reviews.get(0).getPublishedDate());

        for (Review review : reviews) {
            LocalDate publishedDate = review.getPublishedDate();
            assertTrue(uniqueDates.add(publishedDate), "Дата публикации не должна повторяться: " + publishedDate);
            assertNotEquals(DayOfWeek.SATURDAY, publishedDate.getDayOfWeek(), "Дата не должна выпадать на субботу");
            assertTrue(
                    !publishedDate.isAfter(LocalDate.now().plusDays(90)),
                    "Дата публикации не должна уходить слишком далеко вперед: " + publishedDate
            );
        }
    }

    @Test
    void updateOrderDetailAndReviewAndPublishDateAssignsDatesByReviewIdOrder() {
        UUID detailsId = UUID.randomUUID();

        Bot bot = new Bot();
        bot.setCounter(3);

        Review first = reviewForPublicationScheduling(1L, bot, detailsId);
        Review second = reviewForPublicationScheduling(2L, null, detailsId);
        Review third = reviewForPublicationScheduling(3L, null, detailsId);

        OrderDetails orderDetails = new OrderDetails();
        orderDetails.setId(detailsId);
        orderDetails.setReviews(List.of(third, first, second));
        orderDetails.setComment("Комментарий");

        OrderDetailsDTO orderDetailsDTO = OrderDetailsDTO.builder()
                .id(detailsId)
                .reviews(List.of(
                        publicationDto(2L),
                        publicationDto(3L),
                        publicationDto(1L)
                ))
                .comment("Комментарий")
                .build();

        when(orderDetailsService.getOrderDetailById(detailsId)).thenReturn(orderDetails);

        boolean updated = reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetailsDTO);

        assertTrue(updated);
        assertTrue(first.getPublishedDate().isBefore(second.getPublishedDate()));
        assertTrue(second.getPublishedDate().isBefore(third.getPublishedDate()));
        verify(reviewRepository).saveAll(org.mockito.ArgumentMatchers.any());
        verify(reviewRepository, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wholeOrderPublicationUsesOneAggregateQueryAndOneReviewBatchWrite() {
        UUID firstDetailsId = UUID.randomUUID();
        UUID secondDetailsId = UUID.randomUUID();
        Bot bot = new Bot();
        bot.setCounter(3);

        Review firstReview = reviewForPublicationScheduling(1L, bot, firstDetailsId);
        Review secondReview = reviewForPublicationScheduling(2L, bot, secondDetailsId);
        OrderDetails firstDetails = new OrderDetails();
        firstDetails.setId(firstDetailsId);
        firstDetails.setReviews(List.of(firstReview));
        firstDetails.setComment("old first");
        OrderDetails secondDetails = new OrderDetails();
        secondDetails.setId(secondDetailsId);
        secondDetails.setReviews(List.of(secondReview));
        secondDetails.setComment("old second");
        when(orderDetailsService.getOrderDetailsForReviewCheckByOrderId(101L))
                .thenReturn(List.of(firstDetails, secondDetails));

        boolean updated = reviewService.updateOrderDetailsAndReviewsAndPublishDates(
                101L,
                List.of(
                        OrderDetailsDTO.builder()
                                .id(firstDetailsId)
                                .comment("new first")
                                .reviews(List.of(publicationDto(1L)))
                                .build(),
                        OrderDetailsDTO.builder()
                                .id(secondDetailsId)
                                .comment("new second")
                                .reviews(List.of(publicationDto(2L)))
                                .build()
                )
        );

        assertTrue(updated);
        assertEquals("new first", firstDetails.getComment());
        assertEquals("new second", secondDetails.getComment());
        var ordered = org.mockito.Mockito.inOrder(
                orderAggregateMutationLockService,
                orderDetailsService,
                reviewRepository
        );
        ordered.verify(orderAggregateMutationLockService).lock(101L);
        ordered.verify(orderDetailsService).getOrderDetailsForReviewCheckByOrderId(101L);
        ordered.verify(reviewRepository).saveAll(org.mockito.ArgumentMatchers.any());
        verify(orderDetailsService, never()).getOrderDetailById(org.mockito.ArgumentMatchers.any());
        verify(orderDetailsService, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wholeOrderPublicationRejectsMissingReviewBeforeBatchWrite() {
        UUID detailsId = UUID.randomUUID();
        Bot bot = new Bot();
        bot.setCounter(3);
        Review first = reviewForPublicationScheduling(1L, bot, detailsId);
        Review omitted = reviewForPublicationScheduling(2L, bot, detailsId);
        OrderDetails liveDetails = new OrderDetails();
        liveDetails.setId(detailsId);
        liveDetails.setReviews(List.of(first, omitted));
        when(orderDetailsService.getOrderDetailsForReviewCheckByOrderId(101L))
                .thenReturn(List.of(liveDetails));

        boolean updated = reviewService.updateOrderDetailsAndReviewsAndPublishDates(
                101L,
                List.of(OrderDetailsDTO.builder()
                        .id(detailsId)
                        .reviews(List.of(publicationDto(1L)))
                        .build())
        );

        assertFalse(updated);
        verify(reviewRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
        assertEquals(null, first.getPublishedDate());
        assertEquals(null, omitted.getPublishedDate());
    }

    private Review reviewForPublicationScheduling(Long id, Bot bot, UUID detailsId) {
        OrderDetails details = new OrderDetails();
        details.setId(detailsId);

        Review review = new Review();
        review.setId(id);
        review.setText("Готовый текст отзыва " + id);
        review.setBot(bot);
        review.setOrderDetails(details);
        return review;
    }

    private ReviewDTO publicationDto(Long id) {
        return ReviewDTO.builder()
                .id(id)
                .text("Готовый текст отзыва " + id)
                .build();
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
